# txrace

[![CI](https://github.com/sahilkalgutkar/txrace/actions/workflows/ci.yml/badge.svg)](https://github.com/sahilkalgutkar/txrace/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/sahilkalgutkar/txrace/branch/main/graph/badge.svg)](https://codecov.io/gh/sahilkalgutkar/txrace)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

I'm building a tool that runs concurrent JDBC transactions one statement at a
time, walks through the orders they could have run in, and reports any order
whose result no serial execution could have produced.

The bugs I'm after live in service code, not in the database: read a row,
check something, write. That pattern quietly assumes an isolation level it
doesn't have, and it passes every test because tests run one request at a time.

Early days. Nothing runs yet.

## Building

Java 21 or newer. The Maven wrapper fetches Maven itself.

```bash
./mvnw verify
```
