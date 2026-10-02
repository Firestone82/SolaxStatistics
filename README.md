# SolaxStatistics

Automated scraper and reporting tool that collects solar energy data from Solax Cloud and CEZ Distributor portals and sends periodic email summaries.

![Java](https://img.shields.io/badge/Java-21%2B-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-blue)

## About

SolaxStatistics logs into the [Solax Cloud](https://www.solaxcloud.com/user-center/) portal and the [CEZ Distributor PND](https://pnd.cezdistribuce.cz/cezpnd2/external/dashboard/view) portal to pull energy production, consumption, and grid exchange data. It fetches quarter-hour spot electricity prices from spotovaelektrina.cz, calculates costs and earnings, and sends scheduled email reports. Complements [SolaxAutomation](https://github.com/Firestone82/SolaxAutomation), which handles real-time inverter control.

## Features

- Scrapes Solax Cloud for production and consumption history
- Pulls import/export meter readings from CEZ Distributor
- Fetches quarter-hour (15-minute) spot electricity prices from spotovaelektrina.cz
- Calculates energy costs, earnings, and net balance per quarter hour
- Exports an Excel summary with Quarterly, Hourly, Daily, Monthly and Yearly sheets
- Sends email reports via SMTP in English or Czech, including the 10 best export days of the month
- Daily log rotation with compression

## Requirements

- Java 21+
- Maven 3.x
- Google Chrome (the Solax and CEZ portals are scraped with Selenium)
- Solax Cloud account
- CEZ Distributor account
- SMTP server for outbound email

## Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/Firestone82/SolaxStatistics.git
   cd SolaxStatistics
   ```

2. Fill in your credentials and SMTP settings in `src/main/resources/application.yml` (Solax Cloud login, CEZ login, meter ID, tariff prices, email sender/recipient). Set `email.enabled: true` to send the summary email (off by default); `email.recipients` lists each address with the template it gets, `energy-report.html` (English) or `energy-report-cs.html` (Czech). `solax.headless` and `cez.headless` choose whether Chrome runs without a window. When a scraper fails, it logs the page it stopped on and saves a screenshot next to its downloads.

3. Build the project:
   ```bash
   mvn clean package -DskipTests
   ```

4. Run:
   ```bash
   java -jar target/*.jar
   ```
   Downloaded data and summaries are stored in the `data` directory, in a folder per year (e.g. `data/summary/2025/summary_2025-09.xlsx`); files left directly in `data/<source>/` by older versions are moved there on startup.

   On startup it processes the previous month. To process other months, set `summary.from` / `summary.to` (`yyyy-MM`) in `application.yml` or pass them as arguments, e.g. `java -jar target/*.jar --summary.from=2025-09 --summary.to=2025-12`.

## License

This project is provided as-is for personal use. No warranty is offered.
