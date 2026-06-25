
# FRT

> **Function-Level Semantic Reconstruction Testing for Database Management Systems**

FRT (**Function-Level Semantic Reconstruction Testing**) is an automated testing framework for detecting **logic bugs** in Database Management Systems (DBMSs).

FRT is based on the observation that many SQL functions admit multiple **semantically equivalent** formulations. It systematically reconstructs function expressions into equivalent variants and validates their consistency through differential execution. Any inconsistency among equivalent formulations indicates a potential logic bug in the DBMS implementation.

Unlike existing DBMS testing approaches that primarily perform semantic validation at the query or predicate level, FRT explores **function-level semantic reconstruction**, enabling it to exercise diverse execution paths and expose subtle implementation bugs that may remain undetected by existing testing techniques.

---

## ✨ Highlights

- Detects **logic bugs** in DBMSs through differential testing.
- Explores **function-level semantic reconstruction**.
- Automatically reconstructs SQL functions into semantically equivalent variants.
- Exercises diverse execution paths to expose implementation bugs.
- Supports multiple popular DBMSs.

---

## Supported DBMSs

| DBMS | Status |
| :--- | :----: |
| SQLite3 | ✅ |
| MySQL | ✅ |
| TiDB | ✅ |
| CockroachDB | ✅ |
| DM8 | ✅ *(Internal Test)* |

---

# Getting Started

## Requirements

- Java 17 or above
- Maven
- The corresponding DBMS to be tested

## Build

```bash
mvn clean package -DskipTests
```

---

# Running FRT

The following examples illustrate how to run FRT on different DBMSs.

## SQLite3

```bash
java -jar target/sqlancer-*.jar \
    --num-threads 1 \
    --num-tries 1 \
    sqlite3 \
    --oracle FRT
```

---

## MySQL

```bash
java -jar target/sqlancer-*.jar \
    --num-threads 1 \
    --num-tries 1 \
    --username=root \
    --password <password> \
    mysql \
    --oracle FRT
```

---

## TiDB

Start TiDB:

```bash
tiup playground
```

Run FRT:

```bash
java -jar target/sqlancer-*.jar \
    --num-threads 1 \
    --num-tries 1 \
    --username=root \
    --password "" \
    tidb \
    --oracle FRT
```

---

## CockroachDB

Start CockroachDB:

```bash
cockroach start-single-node --insecure
```

Run FRT:

```bash
java -jar target/sqlancer-*.jar \
    --num-threads 1 \
    --num-tries 1 \
    --username=root \
    --password "" \
    cockroachdb \
    --oracle FRT
```

---

# Output

During testing, FRT continuously generates database states and semantically equivalent function-level query variants. Whenever two equivalent queries produce inconsistent results, FRT reports a potential logic bug. Testing logs and detected bugs are written to the corresponding output directory for further analysis.

---

# Experimental Evaluation

We evaluate FRT on **SQLite**, **MySQL**, **TiDB**, and **CockroachDB**. Section **4.2** reports the numbers of logic, error, and crash bugs discovered by FRT. Section **4.3** analyzes the individual contributions of **Function-Level Semantic Reconstruction** and **Boundary-Aware Semantic Amplification** to logic bug detection. Section **4.4** investigates bug latency by tracing the earliest affected versions of confirmed bugs, demonstrating FRT’s ability to uncover long-standing correctness issues that remained hidden for years.

For reproducibility, all experimental artifacts are provided in the [`EVALUATION/`](./EVALUATION) directory of this repository, including bug-triggering SQL test cases, evaluation results, and analysis artifacts. The directory is organized to mirror the evaluation sections in the paper:

- **`EVALUATION/4.2/`**: Bug discovery results for each evaluated DBMS, containing logic, error, and crash bug test cases.
- **`EVALUATION/4.3/`**: Component-wise analysis, separating bugs discovered by **Function-Level Semantic Reconstruction** and **Boundary-Aware Semantic Amplification**.
- **`EVALUATION/4.4/`**: Bug latency analysis, grouping confirmed logic bugs by the earliest affected version (e.g., 2020, 2026).

Within each section, artifacts are further organized by DBMS (**SQLite**, **MySQL**, **TiDB**, and **CockroachDB**), and each SQL file corresponds to a confirmed bug-triggering test case reported in the paper.

```text
EVALUATION/
├── 4.2/   # Bug discovery results
├── 4.3/   # Component contribution analysis
└── 4.4/   # Bug latency analysis

---

# Acknowledgement

FRT is implemented on top of the **SQLancer** framework and extends it with a novel **Function-Level Semantic Reconstruction Testing (FRT)** oracle for detecting DBMS logic bugs.
