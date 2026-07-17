package com.delrisco.trap21;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SeedData {
    private static final Map<String, String> TEXT_FILES = new LinkedHashMap<>();

    static {
        TEXT_FILES.put("pub/README.txt", """
                Managed File Transfer Gateway
                =============================
                Authorized partners must place inbound files in /incoming.
                Completed outbound transfers are retained for 30 days.
                Contact: mft-operations@example.invalid
                """);
        TEXT_FILES.put("pub/partner-onboarding.txt", """
                Partner onboarding checklist
                - Request a vendor account from Operations.
                - Use passive FTP for all transfers.
                - Place files in /incoming using the assigned naming convention.
                """);
        TEXT_FILES.put("outgoing/transfer-queue.csv", """
                job_id,partner,file,status
                MFT-24017,northwind,inventory-0717.csv,queued
                MFT-24018,contoso,settlement-0717.dat,retry
                """);
        TEXT_FILES.put("archive/completed/client-transfer.csv", """
                transfer_id,partner,completed_at,bytes
                TR-88421,northwind,2026-07-16T23:41:02Z,18422
                TR-88422,contoso,2026-07-16T23:43:19Z,90411
                """);
        TEXT_FILES.put("config/mft-config.xml", """
                <mft-gateway environment="production">
                  <listener protocol="ftp" port="21" passive-start="30000" passive-end="30009" />
                  <retention completed-days="30" failed-days="90" />
                </mft-gateway>
                """);
        TEXT_FILES.put("reports/partner-list.csv", """
                partner_id,name,contact,status
                10021,Northwind Distribution,mft@example.invalid,active
                10044,Contoso Logistics,transfer@example.invalid,active
                10057,Fabrikam Wholesale,ops@example.invalid,suspended
                """);
        TEXT_FILES.put("logs/transfer-errors.log", """
                2026-07-16T23:43:18Z WARN MFT-24018 remote checksum mismatch; retry scheduled
                2026-07-17T00:04:57Z INFO MFT-24018 retry 1 accepted
                """);
        TEXT_FILES.put("users/admin/credentials-old.txt", """
                Legacy vendor credentials were migrated to the service vault.
                Remove this file after quarterly access review.
                """);
    }

    private SeedData() {
    }

    static void populate(Path root) throws IOException {
        for (String directory : new String[] {
                "archive/completed",
                "backups/daily",
                "backups/monthly",
                "backups/incoming",
                "config",
                "incoming",
                "logs",
                "outgoing",
                "pub",
                "reports",
                "users/admin",
                "users/backup",
                "users/ftpuser"
        }) {
            Files.createDirectories(root.resolve(directory));
        }

        for (Map.Entry<String, String> entry : TEXT_FILES.entrySet()) {
            Path destination = root.resolve(entry.getKey()).normalize();
            if (!Files.exists(destination)) {
                Files.writeString(destination, entry.getValue().stripLeading(), StandardCharsets.UTF_8);
            }
        }

        createBackup(root.resolve("backups/daily/backup-2026-07-14.zip"), "daily");
        createBackup(root.resolve("backups/monthly/finance-archive-2026-06.zip"), "monthly");
    }

    private static void createBackup(Path destination, String cadence) throws IOException {
        if (Files.exists(destination)) {
            return;
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("manifest.txt"));
            zip.write(("Archive type: " + cadence + "\nCreated by: MFT-BACKUP-02\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("transfer-summary.csv"));
            zip.write("partner,files,bytes\nnorthwind,14,844122\ncontoso,9,430118\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
