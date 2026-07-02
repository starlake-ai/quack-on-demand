//! Run the 22 TPC-H benchmark queries against the Quack-on-Demand FlightSQL edge
//! and report the row count, latency, and first-row preview for each.
//!
//! Run:  cargo run --bin tpch                 # all 22 queries
//!       cargo run --bin tpch -- 1 6 14       # just queries 1, 6 and 14
//!
//! The target schema defaults to `tpch1` (override with QOD_TPCH_SCHEMA); all
//! other connection settings come from the same QOD_* env vars query.rs uses
//! (see README). The defaults connect as the superuser admin to acme/bi.

use std::time::Instant;

use qod::tpch_queries::{qualify, TPCH_QUERIES};
use qod::{QodClient, QodConfig};

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("\ntpch run failed: {err}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), Box<dyn std::error::Error>> {
    let schema = std::env::var("QOD_TPCH_SCHEMA")
        .ok()
        .filter(|v| !v.is_empty());
    let schema = schema.unwrap_or_else(|| "tpch1".to_string());

    let selected: Vec<u32> = std::env::args()
        .skip(1)
        .filter_map(|a| a.parse().ok())
        .collect();
    let queries: Vec<&_> = TPCH_QUERIES
        .iter()
        .filter(|q| selected.is_empty() || selected.contains(&q.id))
        .collect();

    let mut client = QodClient::connect(QodConfig::from_env()).await?;
    println!("Connecting to {}", client.describe());
    println!(
        "Running {} TPC-H query(ies) against schema '{}'\n",
        queries.len(),
        schema
    );

    let mut ok = 0u32;
    let mut failed = 0u32;
    let mut total_ms = 0f64;

    for q in queries {
        let label = format!("Q{:02} {}", q.id, q.title);
        let sql = qualify(q.sql, &schema);
        let started = Instant::now();
        match client.query(&sql).await {
            Ok(result) => {
                let ms = started.elapsed().as_secs_f64() * 1000.0;
                total_ms += ms;
                ok += 1;
                println!(
                    "{label:<42} {ms:6.0} ms  {:6} rows  {}",
                    result.num_rows(),
                    result.preview_row()
                );
            }
            Err(err) => {
                failed += 1;
                println!("{label:<42} FAILED  {err}");
            }
        }
    }

    let avg = total_ms / ok.max(1) as f64;
    println!("\n{ok} ok, {failed} failed, {total_ms:.0} ms total ({avg:.0} ms avg)");

    if failed > 0 {
        std::process::exit(1);
    }
    Ok(())
}
