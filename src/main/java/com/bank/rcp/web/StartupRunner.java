package com.bank.rcp.web;

import com.bank.rcp.core.CatalogService;
import com.bank.rcp.core.ReportService;
import com.bank.rcp.core.ResidencyScanner;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Primes the POC: initial classification + residency scan so the dashboard has state. */
@Component
public class StartupRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);
    private final CatalogService catalog;
    private final ResidencyScanner scanner;
    private final ReportService reports;
    private final EventBus bus;
    private final DataStore store;

    public StartupRunner(CatalogService catalog, ResidencyScanner scanner, ReportService reports, EventBus bus, DataStore store) {
        this.catalog = catalog; this.scanner = scanner; this.reports = reports; this.bus = bus; this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        bus.publish("platform.events", "platform.started", null, "rcp", "startup", Json.map("datasets", store.datasetSource));
        catalog.runClassification(null, "system:startup");
        var scan = scanner.scan(null, "system:startup");
        reports.generate("compliance-posture", "system:startup");
        log.info("RCP ready: {} assets, residency conformance {}% ({} violations). UI: http://localhost:8080", store.assets.size(), scan.get("conformancePct"), scan.get("violations"));
    }
}
