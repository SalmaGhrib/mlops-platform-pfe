package com.mlops.platform.dto;

import java.util.List;

/**
 * Réponse paginée générique pour la recherche dynamique.
 *
 * @param <T> type des éléments retournés
 */
public class SearchPageResponse<T> {

    private List<T> items;
    private long total;
    private int page;
    private int size;
    private boolean hasMore;

    public SearchPageResponse() {
    }

    public SearchPageResponse(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
        this.hasMore = (long) (page + 1) * size < total;
    }

    public SearchPageResponse(List<T> items, long total, int page, int size, boolean hasMore) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
        this.hasMore = hasMore;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }
}
