export default {
  accountId: process.env.CLOUDFLARE_ACCOUNT_ID || "YOUR_CLOUDFLARE_ACCOUNT_ID",
  worker: {
    name: "backstage-uptime-monitor",
    main: "./worker.mjs",
    compatibilityDate: "2026-01-01",
    crons: ["*/5 * * * *"],
    vars: {
      ALERT_TO: process.env.BACKSTAGE_ALERT_TO || "alerts@example.com",
      ALERT_FROM: "Android Backstage Monitor <noreply@android-backstage.com>",
    },
    r2Buckets: [
      {
        binding: "STATE_BUCKET",
        bucketName: process.env.BACKSTAGE_BACKUP_BUCKET || "your-backstage-backups-bucket",
      },
    ],
  },
};
