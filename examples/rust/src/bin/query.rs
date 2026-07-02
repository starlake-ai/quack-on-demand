//! Run a single SQL statement against the Quack-on-Demand FlightSQL edge and
//! print the Arrow result.
//!
//! Run:  cargo run --bin query
//!       cargo run --bin query -- "SELECT count(*) FROM tpch1.customer"
//!
//! See `src/lib.rs` for the tonic + Arrow plumbing. Connection settings come
//! from QOD_* env vars (see README).

use qod::{QodClient, QodConfig};

const DEMO: &str = "SELECT * FROM (VALUES (1, 'duck'), (2, 'quack'), (3, 'lake')) AS t(id, label)";

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprint!("\nquery failed: {err}");
        let mut source = err.source();
        while let Some(cause) = source {
            eprint!("\n  caused by: {cause}");
            source = cause.source();
        }
        eprintln!();
        std::process::exit(1);
    }
}

async fn run() -> Result<(), Box<dyn std::error::Error>> {
    let sql = std::env::args()
        .nth(1)
        .or_else(|| std::env::var("QOD_SQL").ok())
        .unwrap_or_else(|| DEMO.to_string());

    let mut client = QodClient::connect(QodConfig::from_env()).await?;
    println!("Connecting to {}", client.describe());
    println!("SQL: {sql}\n");

    let result = client.query(&sql).await?;

    println!("columns: {}", result.column_summary());
    println!("rows: {}\n", result.num_rows());
    let limit = result.num_rows().min(100);
    for i in 0..limit {
        println!("{}", result.row_as_json(i)?);
    }

    Ok(())
}
