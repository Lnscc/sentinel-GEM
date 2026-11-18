export type StatisticMetric = {
  id: string;
  group: string;
  label: string;
  value: number | null;
  unit: string;
  scope: "INVENTORY" | "PERIOD" | "RUNTIME";
  definition: string;
  from: string | null;
  to: string | null;
  unavailableReason: string | null;
};

export type StatisticsReport = {
  generatedAt: string;
  period: { from: string; to: string };
  visibleVersion: string;
  visibleVersionStatus?: string | null;
  metrics: StatisticMetric[];
  recentRuns: {
    kind: "IMPORT" | "STORY";
    status: string;
    startedAt: string;
    completedAt: string | null;
  }[];
};

export function formatTimestamp(value: string) {
  return new Date(value).toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "long" });
}
