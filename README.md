# 🧊 Snowflake Deep Dive

Snowflake was founded in 2012 and its first product, a cloud data warehouse, was launched in June 2015. The company's
first commercial offering, called the Elastic Data Warehouse, became generally available in 2015 and ran exclusively on
Amazon Web Services. Snowflake went public on September 16, 2020, through an IPO.

## 📑 Table of Contents

1. [Overview: What is Snowflake?](#1-overview-what-is-snowflake)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Storage Layer](#3-storage-layer)
4. [Compute Layer (Virtual Warehouses)](#4-compute-layer-virtual-warehouses)
5. [Cloud Services Layer](#5-cloud-services-layer)
6. [Query Lifecycle & Execution](#6-query-lifecycle--execution)
7. [Concurrency & Performance](#7-concurrency--performance)
8. [Security & Governance](#9-security--governance)
9. [Data Sharing & Cloning](#10-data-sharing--cloning)
10. [Semi-Structured Data Support](#11-semi-structured-data-support)
11. [Advanced Features (Streams, Tasks, Snowpark)](#12-advanced-features-streams-tasks-snowpark)
12. [Integration Ecosystem](#13-integration-ecosystem)
13. [Common Use Cases](#14-common-use-cases)
14. [Architecture Diagram](#15-architecture-diagram)

---

## 1. ✅ Overview: What is Snowflake?

- Cloud-native **Data Cloud Platform**
- Supports **structured**, **semi-structured**, and **unstructured** data
- Available on AWS, Azure, and GCP
- Built for **separation of compute and storage**

---

## 2. 🏛️ High-Level Architecture

```markdown
+----------------------------+
| Cloud Services Layer | <-- Central Brain
+----------------------------+
| Compute Layer | <-- Virtual Warehouses (Isolated MPP)
+----------------------------+
| Storage Layer | <-- Centralized Columnar Data Store
+----------------------------+
```

- Layers are **independently scalable**
- Supports multi-tenancy, elasticity, and performance optimization

---

## 3. 📦 Storage Layer
#### Summary

- Stores data in **cloud object storage** (S3, Blob, GCS)
- Uses **columnar micro-partitions** (~16MB compressed)
- **Self-optimizing** via automatic clustering and pruning: 
- Immutable, versioned storage → supports:
  - **Time Travel** (default is 1 day, but can be extended to 90 days depending on your Snowflake edition): Allows querying historical data versions
  - **Fail-Safe** (7-day recovery): Provides a 7-day fail-safe period for data recovery.
  - **Zero-Copy Cloning**: Enables instant creation of table, schema, or database clones without duplicating the underlying data, saving storage and time.
- Since micro-partitions are immutable, updates/deletes create new partitions.

#### Details
**Micro-Partitioning**:
Data is automatically divided into immutable micro-partitions (typically 50–500MB uncompressed), stored in a compressed, columnar format. Each micro-partition includes metadata such as min/max values, null counts, and clustering information, facilitating efficient query pruning.

Each micro-partition stores rich **metadata**:

| Metadata Tracked              | Description                              |
|------------------------------|------------------------------------------|
| Column Min/Max Values        | For partition pruning                    |
| Null Value Presence          | Helps optimize query filters             |
| Distinct Value Counts        | For stats and query plans                |
| Bloom Filters                | Fast exclusion of non-matching rows      |
| Row Count & Size             | Optimizes query splitting & parallelism  |
| Partition Range (Temporal)   | Helps with Time Travel/Retention         |
---
#### 🔎 How Micro-Partitioning Helps

#### 1. 🚀 Query Performance
- Enables **partition pruning** (skipping partitions that don’t match WHERE filters).
- Minimizes data scanned → reduces cost and improves latency.

#### 2. 📈 Automatic Indexing
- Metadata acts like an **implicit index** — no need for user-created indexes.
- Supports efficient joins, range queries, and aggregates.

#### 3. 📦 Storage Efficiency
- High compression ratios due to columnar storage and similar data patterns.
- Tracks **logical metadata** separately from physical layout.

#### 🧮 Partition Pruning Example
```sql
SELECT * FROM sales WHERE region = 'US' AND order_date BETWEEN '2025-01-01' AND '2025-01-31';
➡️ Snowflake checks micro-partition min/max values of region and order_date and skips partitions that can’t contain matching rows — drastically reducing I/O.
```

#### 🔍 Monitoring Micro-Partitioning
```sql
-- See micro-partition info
SELECT * FROM TABLE(INFORMATION_SCHEMA.MICRO_PARTITIONS)
WHERE TABLE_NAME = 'SALES';

-- Check clustering effectiveness
SELECT SYSTEM$CLUSTERING_INFORMATION('SALES');
```

#### 🧱 Internally in Snowflake:
- Think of each micro-partition as a horizontal slice of the table (e.g., 10,000–50,000 rows).

```markdown
Micro-Partition 1: rows 1–30,000
 ├── column_a → compressed columnar block
 ├── column_b → compressed columnar block
 └── column_c → compressed columnar block

Micro-Partition 2: rows 30,001–60,000
 ├── column_a → ...

```

#### NOTE:
- You can improve the effectiveness of pruning by pre-sorting your data before ingestion as Snowflake scans the values as they come and records the min and max (for each column) at the time of ingestion. So for fields like 'name' each partition  min max might end up with same values[min:a..,max..z].

---

## 4. ⚙️ Compute Layer (Virtual Warehouses)
#### Summary

- Independent MPP clusters called **Virtual Warehouses**
- Can **auto-scale** and **auto-suspend/resume**
- Processes include:
  - Data scan, join, filter, aggregation
- Warehouses are **isolated** (no resource contention)

#### Details

#### 📌 What Is a Virtual Warehouse?
➡️ Independent MPP (Massively Parallel Processing) compute clusters that execute queries. Each warehouse operates in isolation, ensuring workload separation and performance consistency.

➡️ In Snowflake, a **Virtual Warehouse** is a **resizable compute cluster** that provides the **CPU, memory, and temporary storage** required to execute:

➡️ It's Snowflake's abstraction of compute that is **independent of storage**.

> Operations:
- SQL queries
- Data loading/unloading
- DML operations (INSERT, UPDATE, DELETE)
- Stored procedures and tasks

#### 🧠 Key Characteristics of Virtual Warehouses

| Feature                    | Description                                                                 |
|----------------------------|-----------------------------------------------------------------------------|
| **Multi-cluster compute**  | Scales out horizontally with multiple clusters for concurrency              |
| **Isolated compute**       | Each warehouse is isolated → no performance interference                    |
| **Stateless**              | Does not persist data between executions                                    |
| **Auto-suspend/resume**    | Saves cost when idle, resumes on demand                                     |
| **User-defined sizes**     | Sizes: X-Small → 6X-Large (each size = more compute nodes)                  |
| **Billing granularity**    | Charged per-second, 60s minimum                                             |

#### 🧮 Scaling: Warehouse Sizes & Resources

| Size       | vCPUs (Approx) | Use Case                             |
|------------|----------------|--------------------------------------|
| X-Small    | ~1             | Small dev/test jobs                  |
| Small      | ~2             | Light queries, BI dashboards         |
| Medium     | ~4             | Concurrent queries, transformations  |
| Large      | ~8             | Data loading, ML prep, joins         |
| X-Large+   | 16–64+         | Heavy batch jobs, backfills          |

> Each "size" is a multiple of the base compute unit (X-Small).

### 🔄 Execution Flow

#### 1. Query Issued
User runs SQL → sent to **Cloud Services Layer**.

#### 2. Compiled & Optimized
Query is parsed, rewritten, optimized → then dispatched to a **Virtual Warehouse**.

#### 3. Execution
Warehouse:
- Scans micro-partitions
- Executes distributed query plan
- Performs aggregations, joins, filters

### ⚡ Concurrency Scaling (Multi-Cluster Warehouse)

When one cluster is saturated (due to high concurrency), Snowflake can:

- **Automatically start additional clusters** (up to max defined)
- Each cluster runs independently but reads from shared data

```sql
-- Example: Create warehouse with 3 auto-scale clusters
CREATE WAREHOUSE my_wh
  WITH WAREHOUSE_SIZE = 'MEDIUM'
  WAREHOUSE_TYPE = 'STANDARD'
  AUTO_SUSPEND = 300
  AUTO_RESUME = TRUE
  MAX_CLUSTER_COUNT = 3
  SCALING_POLICY = 'ECONOMY';
 ```
### 🧰 Caching in Compute Layer
Caches used by virtual warehouses:

| Cache Type           | Scope          | Description                                     |
| -------------------- | -------------- | ----------------------------------------------- |
| **Result Cache**     | Global (cloud) | Cached result set of previous identical queries |
| **Local Disk Cache** | Per warehouse  | Cached micro-partition files for faster reuse   |
| **Metadata Cache**   | Per warehouse  | Keeps table schema, stats, partition info       |

> 💡 Caches are invalidated automatically on DML or schema changes.

### 🚦 Load Balancing & Parallelism
> Massively parallel processing (MPP): A warehouse is a cluster of compute nodes, each working on a portion of the query (partitioned plan).

> Task scheduling: Queries are split into stages, with parallel workers per stage.

> Dynamic filtering, pruning happen during scan stages.

### ⏳ Auto Suspend / Resume
> Warehouses stop automatically after idle (configurable)

>Automatically resume on demand

> Minimizes cost for non-continuous workloads

- Suspended warehouse = no local disk cache retained.
- Active warehouse uses local disk cache for performance but forgets it when suspended.
- Snowflake relies on its global result cache stored in cloud storage to optimize repeated queries, not on local disk cache during suspension.


```sql
ALTER WAREHOUSE my_wh SET AUTO_SUSPEND = 300;
ALTER WAREHOUSE my_wh SET AUTO_RESUME = TRUE;
```

### 🧾 Monitoring & Tuning
| Tool                                       | Description                               |
| ------------------------------------------ | ----------------------------------------- |
| `QUERY_HISTORY` / `WAREHOUSE_LOAD_HISTORY` | Shows query performance and resource use  |
| `WAREHOUSE_METERING_HISTORY`               | Billing and runtime for cost analysis     |
| Query Profile                              | Visual plan with execution stages & stats |
| Scaling History (UI)                       | Shows cluster scale events over time      |

---

## 5. ☁️ Cloud Services Layer
#### Summary

- Manages **metadata**, **authentication**, **query optimization**
- Performs:
  - Query parsing
  - Cost-based optimization
  - Transactional management (ACID, MVCC)
- Maintains security & audit logs

####  Details

**Metadata Management:**
Maintains metadata for all objects, including tables, views, and micro-partitions, enabling efficient query planning and execution.

**Query Parsing and Optimization:**
Parses SQL queries and generates optimized execution plans using metadata and statistics.

**Security and Access Control:**
Manages authentication, authorization, and role-based access control, supporting features like SSO, MFA, and OAuth.

**Transaction Management:** Ensures ACID compliance and handles concurrency control using multi-version concurrency control (MVCC).

---

## 6. 🔍 Query Lifecycle & Execution

1. **Parse** SQL → AST
2. **Optimize** using metadata and statistics
3. **Distribute** query plan to MPP nodes
4. **Execute** in parallel across warehouse nodes
5. **Cache** results and metadata

---

## 7. 🚀 Concurrency & Performance

- Multi-cluster warehouses allow scaling based on demand
- No locking: MVCC ensures simultaneous reads/writes
- Separate compute clusters can process user groups concurrently

---

## 9. 🔐 Security & Governance

- **Encryption** at rest and in transit
- **Role-Based Access Control (RBAC)**
- **Data Masking**, **Row-Level Security**
- Compliance: **HIPAA**, **SOC 2**, **ISO 27001**, **GDPR**
- Federated Auth (OAuth, SAML, SCIM)

---

## 10. 🔗 Data Sharing & Cloning

- **Secure Data Sharing**: Share live data without copying
- **Zero-Copy Cloning**: Instant dev/test environments

---

## 11. 📑 Semi-Structured Data Support

#### Native Support

Snowflake supports the following semi-structured data formats:

* **JSON**
* **Avro**
* **Parquet**
* **XML**

#### Specialized Data Types

Snowflake provides native data types for storing and querying semi-structured data:

* `VARIANT` — Can store any semi-structured format (JSON, Avro, Parquet, XML).
* `OBJECT` — A collection of key-value pairs (like JSON objects).
* `ARRAY` — An ordered list of values (like JSON arrays).

#### Querying Nested Data

Snowflake extends SQL to support operations on nested fields using dot/bracket notation:

```sql
SELECT
  data:address.city AS city,
  data:purchases[0].item AS first_item
FROM my_table;
```

### 📂 Representing Semi-Structured Data

#### JSON

**Text-based**, schema-less format. Easy to read and write but inefficient for large-scale analytics.

#### Example:

```json
{
  "user_id": "U001",
  "address": {
    "city": "Kathmandu",
    "postal_code": "44600"
  },
  "purchases": [
    {"item": "book", "price": 10.5},
    {"item": "pen", "price": 2.0}
  ]
}
```

In Snowflake, this would be stored as a `VARIANT` column and queried using JSON path expressions.

#### Avro

**Binary, row-based format** designed for efficient serialization. Requires a **schema**.

#### Schema for the above JSON:

```json
{
  "type": "record",
  "name": "User",
  "fields": [
    { "name": "user_id", "type": "string" },
    { "name": "address", "type": {
      "type": "record",
      "name": "Address",
      "fields": [
        { "name": "city", "type": "string" },
        { "name": "postal_code", "type": "string" }
      ]
    }},
    { "name": "purchases", "type": {
      "type": "array",
      "items": {
        "type": "record",
        "name": "Purchase",
        "fields": [
          { "name": "item", "type": "string" },
          { "name": "price", "type": "float" }
        ]
      }
    }}
  ]
}
```

#### Binary Representation

* Schema stored with the file.
* Each record is serialized field by field in binary form.
* Strings encoded with length prefixes.
* Arrays serialized with a block count and end marker.

#### Example for Nested Address List

```json
"address": [
{"name": "Home", "city": "Kathmandu", "zip": "44600"
},
{
"name": "Office", "city": "Lalitpur", "zip": "44700"}
]
```

Schema:

```json
{
  "name": "address",
  "type": {
    "type": "array",
    "items": {
      "type": "record",
      "name": "Location",
      "fields": [
        { "name": "name", "type": "string" },
        { "name": "city", "type": "string" },
        { "name": "zip", "type": "string" }
      ]
    }
  }
}
```

#### Parquet

**Binary, columnar format** optimized for analytics and high-performance queries.

* Stores each field in a **separate column block**.
* Nested structures are **flattened** into columns using dot notation.

#### Example Flattened Columns:

```
user_id
address.city
address.postal_code
purchases.item
purchases.price
```

### 🤞 Format Comparisons

| Feature              | JSON               | Avro                          | Parquet                      |
|----------------------|--------------------|-------------------------------|------------------------------|
| Format               | Text               | Binary                        | Binary (Columnar)            |
| Schema               | None (schema-less) | Required                      | Required                     |
| Compression          | Low                | Medium-High                   | High                         |
| Nested Support       | Yes                | Yes                           | Yes                          |
| Best For             | Interchange (APIs) | Serialization (Hadoop, Kafka) | Analytics (Data Lakes, DWHs) |
| Human Readable       | Yes                | No                            | No                           |
| Pruning Optimization | No                 | Yes (with structure)          | Yes (column-based)           |

### ⚙️ Serialization Formats

#### JSON

Serialized as plain text:

```json
{
  "user_id": "U001",
  "address": {
    "city": "Kathmandu",
    "postal_code": "44600"
  },
  "purchases": [
    {
      "item": "book",
      "price": 10.5
    },
    {
      "item": "pen",
      "price": 2.0
    }
  ]
}
```

#### Avro (Binary)

Serialized with:

* Field-by-field binary encoding
* Length-prefixed strings
* Block-encoded arrays with end markers

```json
[
  "Alice",
  "alice@example.com",
  true
]
```

#### Parquet

Columnar encoding means:

* All `city` values stored together
* All `zip` values stored together
* Excellent for filtering on single fields

### 📉Metadata and Performance

#### Metadata Pruning

* Structured types (`INT`, `STRING`, etc.) store metadata like:

  * **Min/Max values**
  * **Distinct values**
  * **Null counts**

Used by Snowflake to:

* **Skip micro-partitions** (pruning)
* **Speed up queries**

#### ⚠️ Limitation on Semi-Structured Types

> ❌ **`VARIANT`, `ARRAY`, and `OBJECT` types do NOT store metadata like min, max, or distinct values**.

As a result:

* Snowflake **cannot prune partitions** for queries on semi-structured fields.
* **Performance may degrade** compared to structured data.

---

## 📌 Additional Notes

* **Avro requires all records to match the defined schema** (no varying structure per record).
* **Elasticsearch** does **not natively return data in Avro format** — you must convert manually or via a pipeline.

### ✅ Conclusion

* Use **JSON** when flexibility and readability are important.
* Use **Avro** for compact, row-based serialization with a schema.
* Use **Parquet** for efficient, columnar analytics at scale.
* Avoid using `VARIANT` for large-scale filters — prefer structured columns where possible for **metadata pruning**.

> Avro = Row-oriented + Write fast + Streaming

> Parquet = Column-oriented + Read fast + Analytics

---

## 12. 🧰 Advanced Features (Streams, Tasks, Snowpark)

- **Streams** for Change Data Capture (CDC)
- **Tasks** for scheduled/triggered ETL pipelines
- **Snowpark**: Use **Java, Python, Scala** for in-warehouse processing

### Overview

This module explores three powerful Snowflake features that enable advanced data engineering and processing
capabilities:

* **Streams**: For change data capture (CDC)
* **Tasks**: For scheduling and automating ETL pipelines
* **Snowpark**: For using Java, Python, or Scala for in-warehouse data processing

---

### ♻️ Streams: Change Data Capture (CDC)

#### What is a Stream?

A **Stream** in Snowflake is a CDC mechanism that tracks changes (INSERTs, UPDATEs, DELETEs) to a table.

* It doesn’t modify data.
* It tracks **delta changes** since the last time the stream was consumed.

#### Use Cases

* Detecting and replicating changes
* Incremental data loading
* Auditing changes

#### Types of Streams

* **Standard Streams**: Capture changes to the underlying table.
* **Append-Only Streams**: Only captures inserts.

#### Syntax Example

```sql
-- Create a table
CREATE OR REPLACE TABLE orders (
  id INT, customer STRING, amount FLOAT
);

-- Create a stream on the table
CREATE OR REPLACE STREAM orders_stream ON TABLE orders;

-- Check for changes
SELECT * FROM orders_stream;
```

#### Output

Stream will show `_METADATA$ACTION`, `_METADATA$ISUPDATE`, and row-level details.

| ID | CUSTOMER | AMOUNT | \_METADATA\$ACTION |
| -- | -------- | ------ | ------------------ |
| 1  | John     | 50.0   | INSERT             |
| 1  | John     | 70.0   | UPDATE             |

---

### ⏰ Tasks: Scheduled/Triggered ETL Pipelines

#### What is a Task?

A **Task** is a way to define and run scheduled SQL statements, including calling procedures or Snowpark functions.

#### Use Cases

* Scheduled ETL jobs
* Periodic maintenance (purging data)
* Chaining tasks to create workflows

#### Key Features

* Can be **scheduled (cron)** or **triggered (after another task)**
* Supports **dependencies** between tasks

#### Syntax Example

```sql
-- Create a simple task
CREATE OR REPLACE TASK daily_etl
  WAREHOUSE = my_wh
  SCHEDULE = 'USING CRON 0 2 * * * UTC'
AS
  INSERT INTO archive_orders
  SELECT * FROM orders_stream;

-- Start the task
ALTER TASK daily_etl RESUME;
```

#### DAG-Style Task Chaining

```sql
CREATE
TASK task1 ...;
CREATE
TASK task2 AFTER task1 ...;
CREATE
TASK task3 AFTER task2 ...;
```

### 🤖 Snowpark: In-Warehouse Programming

#### What is Snowpark?

Snowpark is a developer framework that lets you write data transformation logic in:

* **Java**
* **Python**
* **Scala**

All logic is **executed inside Snowflake**, close to the data.

#### Key Benefits

* Avoids data movement
* Offers full scalability of Snowflake engine
* Integrates with UDFs, stored procedures, and tasks

#### Python Example

```python
from snowflake.snowpark.session import Session
from snowflake.snowpark.functions import col

session = Session.builder.configs({...}).create()
df = session.table("orders")
df_filtered = df.filter(col("amount") > 100)
df_filtered.write.mode("append").save_as_table("high_value_orders")
```

#### Java Example

```java
Session session = Session.builder().configs(config).create();
DataFrame df = session.table("orders");
DataFrame filtered = df.filter(df.col("amount").gt(100));
filtered.

write().

saveAsTable("high_value_orders");
```

#### Deployment in Snowflake

* You can register Snowpark code as **Stored Procedures**.
* These procedures can be scheduled using **Tasks**.

#### Use Case Example

1. Use Snowpark to filter or enrich data.
2. Use a **Task** to schedule the Snowpark procedure.
3. Use a **Stream** to track incremental changes.

### ⚖️ Comparison Summary

| Feature  | Purpose                   | Trigger                | Language Support     | Common Use Case                 |
| -------- | ------------------------- | ---------------------- | -------------------- | ------------------------------- |
| Streams  | Change Data Capture (CDC) | On table changes       | SQL only             | Incremental ETL, CDC            |
| Tasks    | Automate SQL/Procedures   | Scheduled or triggered | SQL, call procedures | ETL pipelines, alerts           |
| Snowpark | Custom logic in code      | Called manually/task   | Python, Java, Scala  | In-database data transformation |

### 🔍 Conclusion

* **Streams** are ideal for real-time or batch CDC.
* **Tasks** automate workflows and integrate with Streams and Snowpark.
* **Snowpark** unlocks advanced logic in code without moving data out of Snowflake.

You can chain all three together to create a **modern, serverless ETL pipeline** within Snowflake.

---

## 13. 🔗 Integration Ecosystem

- BI: Tableau, Power BI, Looker
- ETL: dbt, Fivetran, Informatica, Airflow
- Languages: Python, R, Java, Scala
- Tools: Jupyter, SageMaker, Apache Spark

---

## 14. 🧠 Common Use Cases

- Enterprise Data Warehousing
- Real-time dashboards
- ML feature engineering (via Snowpark)
- Data sharing between partners
- CDC with Streams and Tasks

---

## 📚 Further Reading

- [Snowflake Documentation](https://docs.snowflake.com)
- [Snowflake Engineering Blog](https://www.snowflake.com/blog/)
- [Modern Data Stack: Snowflake](https://www.datacamp.com/blog/snowflake-architecture)

---



