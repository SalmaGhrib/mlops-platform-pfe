export interface SmartSearchParams {
  q?: string;
  projectType?: string;
  dataType?: string;
  status?: string;
  role?: string;
  page: number;
  size: number;
}

export interface SearchPageResponse<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
}
