import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createSocket } from 'node:dgram';
import { once } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { connect, createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { buildSingBoxConfig, parseProfileLink } from '../src/vpn-config.js';

test('Flow DNS preserves the hostname when a client sends only an IP without TLS',
  { skip: process.platform !== 'win32', timeout: 12000 }, async () => {
    const directory = mkdtempSync(join(tmpdir(), 'warpy-flow-test-'));
    const signal = AbortSignal.timeout(9000);
    const peers = new Set();
    const upstream = createServer(socket => {
      peers.add(socket);
      socket.on('error', () => {});
      let greeted = false;
      socket.on('data', data => {
        if (!greeted) {
          greeted = true;
          socket.write(Buffer.from([5, 0]));
        } else if (data[3] === 3 && data.subarray(5, 5 + data[4]).toString() === 'flow.google.com') {
          upstream.emit('flow-request', data);
        } else {
          socket.end(Buffer.from([5, 1, 0, 1, 0, 0, 0, 0, 0, 0]));
        }
      });
    });
    upstream.listen(0, '127.0.0.1');
    await once(upstream, 'listening');
    const inbound = createServer();
    inbound.listen(0, '127.0.0.1');
    await once(inbound, 'listening');
    const port = inbound.address().port;
    await new Promise(resolve => inbound.close(resolve));
    const profile = parseProfileLink(`socks5://127.0.0.1:${upstream.address().port}`);
    const config = buildSingBoxConfig(profile, { lan: true });
    config.inbounds = [{ type: 'mixed', tag: 'tun-in', listen: '127.0.0.1', listen_port: port },
      { type: 'direct', tag: 'test-dns', listen: '127.0.0.1', listen_port: port, network: 'udp' }];
    config.route.rules.unshift({ inbound: ['test-dns'], action: 'hijack-dns' });
    config.route.auto_detect_interface = false;
    const path = join(directory, 'config.json');
    writeFileSync(path, JSON.stringify(config));
    const core = spawn(resolve('src-tauri/bin/sing-box-x86_64-pc-windows-msvc.exe'), ['run', '-c', path],
      { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
    let coreLog = '';
    core.stderr.on('data', data => coreLog += data);
    const dns = createSocket('udp4');
    let client;
    let dnsRetry;
    try {
      for (let attempt = 0; attempt < 40; attempt++) {
        const ready = await new Promise(resolve => {
          const socket = connect(port, '127.0.0.1');
          socket.once('connect', () => { socket.destroy(); resolve(true); });
          socket.once('error', () => resolve(false));
        });
        if (ready) break;
        assert.equal(core.exitCode, null, coreLog);
        await new Promise(resolve => setTimeout(resolve, 50));
      }
      const query = Buffer.concat([Buffer.from('abcd01000001000000000000', 'hex'),
        Buffer.from([4]), Buffer.from('flow'), Buffer.from([6]), Buffer.from('google'),
        Buffer.from([3]), Buffer.from('com'), Buffer.from('0000010001', 'hex')]);
      const answer = once(dns, 'message', { signal });
      // TCP can start listening before the core has bound its UDP DNS socket.
      dnsRetry = setInterval(() => dns.send(query, port, '127.0.0.1'), 200);
      dns.send(query, port, '127.0.0.1');
      const [response] = await answer;
      clearInterval(dnsRetry);
      assert.equal(response.readUInt16BE(6), 1, 'one synthetic A record');
      const ip = response.subarray(-4);
      assert.equal(ip[0], 198);
      assert.ok(ip[1] === 18 || ip[1] === 19);
      client = connect(port, '127.0.0.1');
      client.on('error', () => {});
      await once(client, 'connect', { signal });
      let received = once(client, 'data', { signal });
      client.write(Buffer.from([5, 1, 0]));
      await received;
      const captured = once(upstream, 'flow-request', { signal });
      client.write(Buffer.concat([Buffer.from([5, 1, 0, 1]), ip, Buffer.from([1, 187])]));
      // Supply no ClientHello: server routing must not depend on sniffing.
      const [request] = await captured;
      assert.equal(request[3], 3, 'SOCKS upstream must receive a hostname, not the fake IP');
      assert.equal(request.subarray(5, 5 + request[4]).toString(), 'flow.google.com');
    } finally {
      clearInterval(dnsRetry);
      client?.destroy();
      for (const peer of peers) peer.destroy();
      dns.close();
      upstream.close();
      core.kill();
      await once(core, 'close');
      rmSync(directory, { recursive: true, force: true });
    }
  });
