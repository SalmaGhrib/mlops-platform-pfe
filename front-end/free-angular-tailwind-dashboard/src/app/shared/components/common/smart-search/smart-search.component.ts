import { Component, EventEmitter, Input, OnDestroy, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, Subject, Subscription } from 'rxjs';
import { debounceTime, switchMap, tap } from 'rxjs/operators';
import { SearchPageResponse, SmartSearchParams } from './smart-search.types';

@Component({
  selector: 'app-smart-search',
  imports: [CommonModule, FormsModule],
  templateUrl: './smart-search.component.html',
})
export class SmartSearchComponent<T = any> implements OnInit, OnDestroy {
  @Input() searchFn!: (params: SmartSearchParams) => Observable<SearchPageResponse<T>>;
  @Input() placeholder = 'Rechercher...';
  @Input() showProjectType = true;
  @Input() showDataType = true;
  @Input() showStatus = false;
  @Input() showRole = false;
  @Input() pageSize = 12;

  @Output() itemsChange = new EventEmitter<T[]>();
  @Output() loadingChange = new EventEmitter<boolean>();
  @Output() totalChange = new EventEmitter<number>();

  items: T[] = [];
  total = 0;
  page = 0;
  hasMore = false;
  loading = false;
  error: string | null = null;

  q = '';
  projectType = '';
  dataType = '';
  status = '';
  role = '';

  private querySubject = new Subject<void>();
  private subscription: Subscription | undefined;

  ngOnInit(): void {
    this.subscription = this.querySubject
      .pipe(
        debounceTime(300),
        switchMap(() => this.fetch())
      )
      .subscribe({
        error: () => this.failSearch(),
      });

    // Premier chargement immédiat (sans debounce) pour un affichage rapide.
    this.loading = true;
    this.loadingChange.emit(true);
    this.fetch().subscribe({
      error: () => this.failSearch(),
    });
  }

  private failSearch(): void {
    this.error = 'Erreur lors de la recherche.';
    this.loading = false;
    this.loadingChange.emit(false);
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.querySubject.complete();
  }

  onInputChange(): void {
    this.querySubject.next();
  }

  onFilterChange(): void {
    this.page = 0;
    this.querySubject.next();
  }

  previousPage(): void {
    if (this.page > 0) {
      this.page--;
      this.querySubject.next();
    }
  }

  nextPage(): void {
    if (this.hasMore) {
      this.page++;
      this.querySubject.next();
    }
  }

  reload(): void {
    this.querySubject.next();
  }

  resetAndReload(): void {
    this.page = 0;
    this.querySubject.next();
  }

  get totalPages(): number {
    return this.pageSize > 0 ? Math.ceil(this.total / this.pageSize) : 0;
  }

  get fromCount(): number {
    if (this.total === 0) {
      return 0;
    }
    return this.page * this.pageSize + 1;
  }

  get toCount(): number {
    return Math.min((this.page + 1) * this.pageSize, this.total);
  }

  private fetch() {
    const params: SmartSearchParams = {
      q: this.q.trim() || undefined,
      projectType: this.projectType || undefined,
      dataType: this.dataType || undefined,
      status: this.status || undefined,
      role: this.role || undefined,
      page: this.page,
      size: this.pageSize,
    };

    this.loading = true;
    this.loadingChange.emit(true);

    return this.searchFn(params).pipe(
      tap((result) => {
        this.items = result.items;
        this.total = result.total;
        this.page = result.page;
        this.hasMore = result.hasMore;
        this.error = null;
        this.loading = false;
        this.loadingChange.emit(false);
        this.itemsChange.emit(this.items);
        this.totalChange.emit(this.total);
      })
    );
  }
}
