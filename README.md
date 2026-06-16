# FRT

**FRT (Function-Level Semantic Reconstruction Testing)** is an automated testing framework for detecting **logic bugs** in Database Management Systems (DBMSs). It is built on the observation that many SQL functions admit multiple **semantically equivalent** formulations. FRT systematically reconstructs SQL functions into equivalent variants and validates their consistency through differential execution.

Unlike existing DBMS testing approaches that primarily focus on query-level or predicate-level semantic validation, FRT performs **function-level semantic equivalence reconstruction**, enabling it to exercise diverse execution paths and expose subtle implementation bugs in DBMS function implementations.

## Supported DBMSs

- SQLite3
- MySQL
- TiDB
- CockroachDB
- DM8 (Internal Test)

---

# Getting Started

## Requirements

- Java 17 or above
- Maven
- The corresponding DBMS to be tested

---

## Build FRT

```bash
mvn clean package -DskipTests
```

---

# Running FRT

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

## SQLite3

Run FRT:

```bash
java -jar target/sqlancer-*.jar \
    --num-threads 1 \
    --num-tries 1 \
    sqlite3 \
    --oracle FRT
```

---

## MySQL

Run FRT:

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

# Output

FRT continuously generates database states and semantically equivalent function-level query variants during testing. Whenever two equivalent queries produce inconsistent results, FRT reports a potential logic bug. Detected bugs and testing logs are written to the output directory for further analysis.

---

# Citation

If you find this project useful for your research, please consider giving this repository a ⭐ and citing our paper:

```bibtex
@inproceedings{frt2027,
  title={FRT: Testing Database Systems via Function-Level Semantic Equivalence Reconstruction},
  author={...},
  booktitle={Proceedings of the IEEE/ACM International Conference on Software Engineering},
  year={2027}
}
```
