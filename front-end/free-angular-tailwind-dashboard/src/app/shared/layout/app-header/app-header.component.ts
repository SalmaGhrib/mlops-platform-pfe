import { Component, ElementRef, ViewChild } from '@angular/core';
import { SidebarService } from '../../services/sidebar.service';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { firstValueFrom, Subject, Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { ThemeToggleButtonComponent } from '../../components/common/theme-toggle/theme-toggle-button.component';
import { NotificationDropdownComponent } from '../../components/header/notification-dropdown/notification-dropdown.component';
import { UserDropdownComponent } from '../../components/header/user-dropdown/user-dropdown.component';
import { ProjectService } from '../../../core/services/project.service';
import { ModelRegistryService } from '../../../core/services/model-registry.service';

interface SearchResult {
  label: string;
  subtitle: string;
  route: string;
  type: 'project' | 'model' | 'deployment';
}

@Component({
  selector: 'app-header',
  imports: [
    CommonModule,
    RouterModule,
    ThemeToggleButtonComponent,
    NotificationDropdownComponent,
    UserDropdownComponent,
  ],
  templateUrl: './app-header.component.html',
})
export class AppHeaderComponent {
  isApplicationMenuOpen = false;
  readonly isMobileOpen$;

  pageTitle = 'Accueil';
  searchQuery = '';
  searchResults: SearchResult[] = [];
  searchOpen = false;
  searchLoading = false;

  private readonly searchSubject = new Subject<string>();
  private readonly subscription = new Subscription();

  @ViewChild('searchInput') searchInput!: ElementRef<HTMLInputElement>;

  private readonly pageTitles: Record<string, string> = {
    '/': 'Accueil',
    '/dashboard': 'Tableau de bord',
    '/my-projects': 'Mes Projets',
    '/upload-training': 'Upload & Training',
    '/model-registry': 'Model Registry',
    '/model-deployments': 'Modèles Déployés',
    '/accessible-models': 'Modèles Accessibles',
    '/prediction-test': 'Test de Prédiction',
    '/my-monitoring': 'Mon Monitoring',
    '/users-management': 'Gestion des Utilisateurs',
    '/admin-monitoring': 'Monitoring Admin',
    '/profile': 'Mon Profil',
  };

  constructor(
    public sidebarService: SidebarService,
    private readonly router: Router,
    private readonly projectService: ProjectService,
    private readonly registryService: ModelRegistryService
  ) {
    this.isMobileOpen$ = this.sidebarService.isMobileOpen$;

    this.subscription.add(
      this.router.events.subscribe(() => {
        this.updatePageTitle();
      })
    );

    this.subscription.add(
      this.searchSubject
        .pipe(debounceTime(300), distinctUntilChanged(), switchMap((query) => this.performSearch(query)))
        .subscribe((results) => {
          this.searchResults = results;
          this.searchLoading = false;
        })
    );
  }

  handleToggle() {
    if (window.innerWidth >= 1280) {
      this.sidebarService.toggleExpanded();
    } else {
      this.sidebarService.toggleMobileOpen();
    }
  }

  toggleApplicationMenu() {
    this.isApplicationMenuOpen = !this.isApplicationMenuOpen;
  }

  ngAfterViewInit() {
    document.addEventListener('keydown', this.handleKeyDown);
  }

  ngOnDestroy() {
    this.subscription.unsubscribe();
    document.removeEventListener('keydown', this.handleKeyDown);
  }

  handleKeyDown = (event: KeyboardEvent) => {
    if ((event.metaKey || event.ctrlKey) && event.key === 'k') {
      event.preventDefault();
      this.searchInput?.nativeElement.focus();
    }
  };

  private updatePageTitle(): void {
    const url = this.router.url.split('?')[0].split('#')[0];

    let title = this.pageTitles[url];

    if (!title) {
      for (const [key, value] of Object.entries(this.pageTitles)) {
        if (url.startsWith(key + '/')) {
          title = value;
          break;
        }
      }
    }

    this.pageTitle = title ?? 'MLOps Pilot';
  }

  onSearchInput(query: string): void {
    this.searchQuery = query.trim();

    if (this.searchQuery.length < 2) {
      this.searchResults = [];
      this.searchLoading = false;
      this.searchOpen = false;
      return;
    }

    this.searchOpen = true;
    this.searchLoading = true;
    this.searchSubject.next(this.searchQuery);
  }

  private performSearch(query: string): Promise<SearchResult[]> {
    if (query.length < 2) {
      return Promise.resolve([]);
    }

    const results: SearchResult[] = [];

    const projectsPromise = firstValueFrom(
      this.projectService.searchProjects({ q: query, page: 0, size: 4 })
    ).then((page) => {
      const mapped = (page.items ?? []).map((project) => ({
        label: project.name ?? 'Projet',
        subtitle: 'Projet ML',
        route: '/my-projects',
        type: 'project' as const,
      }));
      results.push(...mapped);
      return mapped;
    }).catch(() => []);

    const modelsPromise = firstValueFrom(
      this.registryService.searchMyModels({ q: query, page: 0, size: 4 })
    ).then((page) => {
      const mapped = (page.items ?? []).map((model) => ({
        label: model.registeredModelName ?? model.modelName ?? 'Modèle',
        subtitle: 'Modèle enregistré',
        route: '/model-registry',
        type: 'model' as const,
      }));
      results.push(...mapped);
      return mapped;
    }).catch(() => []);

    const deploymentsPromise = firstValueFrom(
      this.registryService.searchDeployments('owned', { q: query, page: 0, size: 4 })
    ).then((page) => {
      const mapped = (page.items ?? []).map((deployment) => ({
        label: deployment.registeredModelName ?? 'Déploiement',
        subtitle: `Déploiement · ${deployment.deploymentStatus ?? ''}`,
        route: '/model-deployments',
        type: 'deployment' as const,
      }));
      results.push(...mapped);
      return mapped;
    }).catch(() => []);

    return Promise.all([projectsPromise, modelsPromise, deploymentsPromise]).then(
      () => results
    );
  }

  navigateTo(route: string): void {
    this.searchOpen = false;
    this.searchQuery = '';
    this.searchResults = [];
    this.router.navigate([route]);
  }

  onSearchBlur(): void {
    setTimeout(() => {
      this.searchOpen = false;
    }, 150);
  }
}
