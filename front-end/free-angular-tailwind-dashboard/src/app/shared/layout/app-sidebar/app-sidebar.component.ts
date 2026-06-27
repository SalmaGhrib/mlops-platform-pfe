import { CommonModule } from '@angular/common';
import {
  Component,
  ElementRef,
  QueryList,
  ViewChildren,
  ChangeDetectorRef,
  OnInit,
  OnDestroy,
} from '@angular/core';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { combineLatest, Subscription } from 'rxjs';

import { SidebarService } from '../../services/sidebar.service';
import { SafeHtmlPipe } from '../../pipe/safe-html.pipe';
import { SidebarWidgetComponent } from './app-sidebar-widget.component';
import { AuthService } from '../../../core/services/auth.service';

type NavItem = {
  name: string;
  icon: string;
  path?: string;
  new?: boolean;
  subItems?: { name: string; path: string; pro?: boolean; new?: boolean }[];
};

type NavGroup = {
  title: string;
  items: NavItem[];
};

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, RouterModule, SafeHtmlPipe, SidebarWidgetComponent],
  templateUrl: './app-sidebar.component.html',
})
export class AppSidebarComponent implements OnInit, OnDestroy {
  openSubmenu: string | null | number = null;
  subMenuHeights: { [key: string]: number } = {};

  @ViewChildren('subMenu') subMenuRefs!: QueryList<ElementRef>;

  readonly isExpanded$;
  readonly isMobileOpen$;
  readonly isHovered$;

  isAdmin = false;

  private subscription: Subscription = new Subscription();

  constructor(
    public sidebarService: SidebarService,
    private router: Router,
    private cdr: ChangeDetectorRef,
    private authService: AuthService
  ) {
    this.isExpanded$ = this.sidebarService.isExpanded$;
    this.isMobileOpen$ = this.sidebarService.isMobileOpen$;
    this.isHovered$ = this.sidebarService.isHovered$;
  }

  get navGroups(): NavGroup[] {
    const groups: NavGroup[] = [
      {
        title: '',
        items: [
          {
            icon: this.dashboardIcon,
            name: 'Dashboard',
            path: '/',
          },
        ],
      },
      {
        title: 'WORKSPACE',
        items: [
          {
            icon: this.projectsIcon,
            name: 'My Projects',
            path: '/my-projects',
          },
          {
            icon: this.uploadIcon,
            name: 'Upload & Training',
            path: '/upload-training',
          },
        ],
      },
      {
        title: 'MLOPS',
        items: [
          {
            icon: this.pipelineIcon,
            name: 'Pipeline Runs',
            path: '/pipeline-runs',
          },
          {
            icon: this.experimentIcon,
            name: 'Experiments',
            path: '/experiments',
          },
          {
            icon: this.registryIcon,
            name: 'Model Registry',
            path: '/model-registry',
          },
          {
            icon: this.deployedIcon,
            name: 'Deployed Models',
            path: '/model-deployments',
          },
          {
            icon: this.accessibleModelsIcon,
            name: 'Accessible Models',
            path: '/accessible-models',
          },
          {
            icon: this.predictionIcon,
            name: 'Prediction Test',
            path: '/prediction-test',
          },
        ],
      },
      {
        title: 'ACCOUNT',
        items: [
          {
            icon: this.profileIcon,
            name: 'My Profile',
            path: '/profile',
          },
        ],
      },
    ];

    if (this.isAdmin) {
      groups[3].items.push(
        {
          icon: this.usersIcon,
          name: 'Gestion des Utilisateurs',
          path: '/users-management',
        },
        {
          icon: this.monitoringIcon,
          name: 'Monitoring',
          path: '/admin-monitoring',
        }
      );
    }

    return groups;
  }

  ngOnInit(): void {
    this.refreshRole();

    this.subscription.add(
      this.authService.role$.subscribe(() => {
        this.refreshRole();
      })
    );

    this.subscription.add(
      this.router.events.subscribe((event) => {
        if (event instanceof NavigationEnd) {
          this.refreshRole();
          this.setActiveMenuFromRoute(this.router.url);
        }
      })
    );

    this.subscription.add(
      combineLatest([
        this.isExpanded$,
        this.isMobileOpen$,
        this.isHovered$,
      ]).subscribe(([isExpanded, isMobileOpen, isHovered]) => {
        if (!isExpanded && !isMobileOpen && !isHovered) {
          this.cdr.detectChanges();
        }
      })
    );

    this.setActiveMenuFromRoute(this.router.url);
  }

  ngOnDestroy(): void {
    this.subscription.unsubscribe();
  }

  isActive(path: string): boolean {
    if (!path) {
      return false;
    }

    if (path === '/') {
      return this.router.url === '/';
    }

    return this.router.url === path || this.router.url.startsWith(path + '/');
  }

  toggleSubmenu(groupIndex: number, itemIndex: number): void {
    const key = `${groupIndex}-${itemIndex}`;

    if (this.openSubmenu === key) {
      this.openSubmenu = null;
      this.subMenuHeights[key] = 0;
    } else {
      this.openSubmenu = key;

      setTimeout(() => {
        const el = document.getElementById(key);
        if (el) {
          this.subMenuHeights[key] = el.scrollHeight;
          this.cdr.detectChanges();
        }
      });
    }
  }

  onSidebarMouseEnter(): void {
    this.isExpanded$
      .subscribe((expanded) => {
        if (!expanded) {
          this.sidebarService.setHovered(true);
        }
      })
      .unsubscribe();
  }

  onSubmenuClick(): void {
    if (window.innerWidth < 1280) {
      this.sidebarService.setMobileOpen(false);
    }
  }

  private refreshRole(): void {
    this.isAdmin = this.authService.isAdmin();
    this.cdr.detectChanges();
  }

  private setActiveMenuFromRoute(currentUrl: string): void {
    this.navGroups.forEach((group, groupIndex) => {
      group.items.forEach((nav, itemIndex) => {
        if (nav.subItems) {
          const hasActiveSubItem = nav.subItems.some((subItem) =>
            currentUrl.startsWith(subItem.path)
          );

          if (hasActiveSubItem) {
            this.openSubmenu = `${groupIndex}-${itemIndex}`;

            setTimeout(() => {
              const el = document.getElementById(`${groupIndex}-${itemIndex}`);
              if (el) {
                this.subMenuHeights[`${groupIndex}-${itemIndex}`] =
                  el.scrollHeight;
                this.cdr.detectChanges();
              }
            });
          }
        }
      });
    });
  }

  private dashboardIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M4 4H10V10H4V4ZM14 4H20V10H14V4ZM4 14H10V20H4V14ZM14 14H20V20H14V14Z" fill="currentColor"/>
    </svg>
  `;

  private projectsIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M4 5H20V19H4V5ZM6 7V17H18V7H6Z" fill="currentColor"/>
    </svg>
  `;

  private uploadIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M12 3L17 8H14V14H10V8H7L12 3ZM5 18H19V20H5V18Z" fill="currentColor"/>
    </svg>
  `;

  private pipelineIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M4 6H20V8H4V6ZM4 11H20V13H4V11ZM4 16H20V18H4V16Z" fill="currentColor"/>
    </svg>
  `;

  private experimentIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M7 3H17V5H16V10L20 18C20.7 19.3 19.8 21 18.3 21H5.7C4.2 21 3.3 19.3 4 18L8 10V5H7V3ZM10 5V10.5L6 19H18L14 10.5V5H10Z" fill="currentColor"/>
    </svg>
  `;

  private registryIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M4 4H20V20H4V4ZM6 6V8H18V6H6ZM6 10V12H18V10H6ZM6 14V18H18V14H6Z" fill="currentColor"/>
    </svg>
  `;

  private deployedIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M12 2C6.48 2 2 6.48 2 12C2 17.52 6.48 22 12 22C17.52 22 22 17.52 22 12C22 6.48 17.52 2 12 2ZM11 6H13V12L17 15L15.8 16.6L11 13V6Z" fill="currentColor"/>
    </svg>
  `;

  private accessibleModelsIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M12 2L20 6V12C20 17.2 16.6 21.7 12 23C7.4 21.7 4 17.2 4 12V6L12 2ZM12 4.2L6 7.2V12C6 16.1 8.5 19.6 12 20.9C15.5 19.6 18 16.1 18 12V7.2L12 4.2ZM11 8H13V13H11V8ZM11 15H13V17H11V15Z" fill="currentColor"/>
    </svg>
  `;

  private predictionIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M4 3.75C3.58579 3.75 3.25 4.08579 3.25 4.5V19.5C3.25 19.9142 3.58579 20.25 4 20.25H20C20.4142 20.25 20.75 19.9142 20.75 19.5V4.5C20.75 4.08579 20.4142 3.75 20 3.75H4ZM4.75 5.25H19.25V18.75H4.75V5.25ZM7 8.25H17V9.75H7V8.25ZM7 11.25H13V12.75H7V11.25ZM15.2 13.2L17.8 15.75L15.2 18.3L14.15 17.25L15.7 15.75L14.15 14.25L15.2 13.2Z" fill="currentColor"/>
    </svg>
  `;

  private profileIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M12 12C14.21 12 16 10.21 16 8C16 5.79 14.21 4 12 4C9.79 4 8 5.79 8 8C8 10.21 9.79 12 12 12ZM12 14C8.67 14 6 15.34 6 17V20H18V17C18 15.34 15.33 14 12 14Z" fill="currentColor"/>
    </svg>
  `;

  private usersIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M16 11C17.66 11 19 9.66 19 8C19 6.34 17.66 5 16 5C14.34 5 13 6.34 13 8C13 9.66 14.34 11 16 11ZM8 11C9.66 11 11 9.66 11 8C11 6.34 9.66 5 8 5C6.34 5 5 6.34 5 8C5 9.66 6.34 11 8 11ZM8 13C5.33 13 3 14.34 3 16V19H13V16C13 14.34 10.67 13 8 13ZM16 13C15.69 13 15.39 13.02 15.1 13.07C16.26 13.91 17 14.97 17 16V19H21V16C21 14.34 18.67 13 16 13Z" fill="currentColor"/>
    </svg>
  `;

  private monitoringIcon = `
    <svg width="1em" height="1em" viewBox="0 0 24 24" fill="none">
      <path d="M4 19V5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
      <path d="M4 19H20" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
      <path d="M7 15L10.5 11.5L13 14L18 8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
      <circle cx="18" cy="8" r="1.5" fill="currentColor"/>
    </svg>
  `;
}