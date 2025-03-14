import { DatabaseId } from "./database";
import { DownloadPermission } from "./permissions";
import type { DatetimeUnit } from "metabase-types/api/query";

export interface DatasetColumn {
  display_name: string;
  source: string;
  name: string;
  remapped_to_column?: DatasetColumn;
  unit?: DatetimeUnit;
}

export interface DatasetData {
  rows: any[][];
  cols: DatasetColumn[];
  rows_truncated: number;
  download_perms?: DownloadPermission;
}

export interface Dataset {
  data: DatasetData;
  database_id: DatabaseId;
  row_count: number;
  running_time: number;
}
