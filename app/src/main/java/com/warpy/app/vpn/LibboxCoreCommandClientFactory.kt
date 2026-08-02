package com.warpy.app.vpn

import com.warpy.app.vpn.session.CoreCommand
import com.warpy.app.vpn.session.CoreCommandClient
import com.warpy.app.vpn.session.CoreCommandClientFactory
import com.hiddify.core.libbox.CommandClientHandler
import com.hiddify.core.libbox.CommandClientOptions
import com.hiddify.core.libbox.Libbox

internal class LibboxCoreCommandClientFactory(
    private val handlerFactory: () -> CommandClientHandler,
) : CoreCommandClientFactory {
    override fun create(command: CoreCommand): CoreCommandClient {
        val client = Libbox.newCommandClient(
            handlerFactory(),
            CommandClientOptions().apply {
                addCommand(
                    when (command) {
                        CoreCommand.OutboundGroup -> Libbox.CommandGroup
                        CoreCommand.Connections -> Libbox.CommandConnections
                    },
                )
            },
        )
        return object : CoreCommandClient {
            override fun connect() = client.connect()

            override fun selectOutbound(groupTag: String, outboundTag: String) {
                client.selectOutbound(groupTag, outboundTag)
            }

            override fun closeConnections() = client.closeConnections()

            override fun close() {
                client.disconnect()
            }
        }
    }
}
