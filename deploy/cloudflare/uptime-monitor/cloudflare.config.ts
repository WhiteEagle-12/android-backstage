export default {
  accountId: "a5dfebd8e1f1aba160f0609725631b67",
  worker: {
    name: "backstage-uptime-monitor",
    main: "./worker.mjs",
    compatibilityDate: "2026-01-01",
    crons: ["*/5 * * * *"],
    vars: {
      ALERT_TO: "gavin.pate.mt@gmail.com",
      ALERT_FROM: "Android Backstage Monitor <noreply@android-backstage.com>",
    },
    r2Buckets: [
      {
        binding: "STATE_BUCKET",
        bucketName: "backstage-state-backups",
      },
    ],
  },
};
