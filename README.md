# SolaxStatistics

Automated scraper and reporting tool that collects solar energy data from Solax Cloud and CEZ Distributor portals and sends periodic email summaries.

![Java](https://img.shields.io/badge/Java-17%2B-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-blue)

## About

SolaxStatistics logs into the [Solax Cloud](https://global.solaxcloud.com) portal and the [CEZ Distributor](https://dip.cezdistribuce.cz) portal to pull energy production, consumption, and grid exchange data. It fetches spot electricity prices from spotovaelektrina.cz, calculates costs and earnings, and sends scheduled email reports. Complements [SolaxAutomation](https://github.com/Firestone82/SolaxAutomation), which handles real-time inverter control.

## Features

- Scrapes Solax Cloud for production and consumption history
- Pulls import/export meter readings from CEZ Distributor
- Fetches spot electricity prices from spotovaelektrina.cz
- Calculates energy costs, earnings, and net balance
- Sends scheduled email reports via SMTP
- Daily log rotation with compression

## Requirements

- Java 17+
- Maven 3.x
- Solax Cloud account
- CEZ Distributor account
- SMTP server for outbound email

## Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/Firestone82/SolaxStatistics.git
   cd SolaxStatistics
   ```

2. Fill in your credentials and SMTP settings in `src/main/resources/application.yml` (Solax Cloud login, CEZ login, meter ID, tariff prices, email sender/recipient).

3. Build the project:
   ```bash
   mvn clean package -DskipTests
   ```

4. Run:
   ```bash
   java -jar target/*.jar
   ```

## License

This project is provided as-is for personal use. No warranty is offered.
