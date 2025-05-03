# 🧊 Snowflake Deep Dive

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

- Native support for **JSON**, **Avro**, **Parquet**, **XML**
- Use `VARIANT`, `OBJECT`, `ARRAY` types
- SQL extensions for parsing/transforming nested structures

---

## 12. 🧰 Advanced Features (Streams, Tasks, Snowpark)

- **Streams** for Change Data Capture (CDC)
- **Tasks** for scheduled/triggered ETL pipelines
- **Snowpark**: Use **Java, Python, Scala** for in-warehouse processing

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



