use minisign_verify::{PublicKey, Signature};
use std::{env, fs, path::Path};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let arguments: Vec<_> = env::args_os().skip(1).collect();
    if arguments.len() != 3 {
        return Err("usage: verify_updater_signature <public-key> <signature> <artifact>".into());
    }

    let public_key = PublicKey::from_file(Path::new(&arguments[0]))?;
    let signature = Signature::from_file(Path::new(&arguments[1]))?;
    let artifact = fs::read(&arguments[2])?;
    public_key.verify(&artifact, &signature, false)?;
    println!("Updater signature verified.");
    Ok(())
}
