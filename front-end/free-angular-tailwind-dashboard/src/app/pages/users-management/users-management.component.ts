import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Observable } from 'rxjs';
import { AuthService, UserResponse } from '../../core/services/auth.service';
import { PageBreadcrumbComponent } from '../../shared/components/common/page-breadcrumb/page-breadcrumb.component';
import { SmartSearchComponent } from '../../shared/components/common/smart-search/smart-search.component';
import {
  SearchPageResponse,
  SmartSearchParams
} from '../../shared/components/common/smart-search/smart-search.types';

@Component({
  selector: 'app-users-management',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    SmartSearchComponent
  ],
  templateUrl: './users-management.component.html',
  styles: ``
})
export class UsersManagementComponent implements OnInit {
  @ViewChild(SmartSearchComponent) smartSearch!: SmartSearchComponent<UserResponse>;

  users: UserResponse[] = [];
  totalUsers = 0;

  loading = false;
  errorMessage = '';
  successMessage = '';

  roleUpdateLoadingId: number | null = null;
  statusUpdateLoadingId: number | null = null;
  deleteLoadingId: number | null = null;

  constructor(private authService: AuthService) {}

  ngOnInit(): void {
    // Le composant app-smart-search charge automatiquement la première page.
  }

  searchUsers = (params: SmartSearchParams): Observable<SearchPageResponse<UserResponse>> =>
    this.authService.searchUsers(params);

  onSearchItems(users: UserResponse[]): void {
    this.users = users;
  }

  onTotalChange(total: number): void {
    this.totalUsers = total;
  }

  refresh(): void {
    this.smartSearch?.resetAndReload();
  }

  toggleUserRole(user: UserResponse): void {
    const newRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN';

    const confirmation = confirm(
      `Voulez-vous vraiment changer le rôle de ${user.firstName} ${user.lastName} en ${newRole} ?`
    );

    if (!confirmation) {
      return;
    }

    this.roleUpdateLoadingId = user.id;
    this.errorMessage = '';
    this.successMessage = '';

    this.authService.updateUserRole(user.id, newRole).subscribe({
      next: (updatedUser: UserResponse) => {
        this.users = this.users.map(existingUser =>
          existingUser.id === updatedUser.id ? updatedUser : existingUser
        );

        this.roleUpdateLoadingId = null;
        this.successMessage = `Le rôle de ${updatedUser.firstName} ${updatedUser.lastName} a été mis à jour en ${updatedUser.role}.`;
      },
      error: (error) => {
        console.error('Update role error:', error);
        this.roleUpdateLoadingId = null;
        this.errorMessage = 'Erreur lors de la modification du rôle.';
      }
    });
  }

  toggleUserStatus(user: UserResponse): void {
    const newStatus = user.status === 'ACTIVE' ? 'BLOCKED' : 'ACTIVE';

    const confirmation = confirm(
      newStatus === 'BLOCKED'
        ? `Voulez-vous vraiment bloquer le compte de ${user.firstName} ${user.lastName} ?`
        : `Voulez-vous vraiment réactiver le compte de ${user.firstName} ${user.lastName} ?`
    );

    if (!confirmation) {
      return;
    }

    this.statusUpdateLoadingId = user.id;
    this.errorMessage = '';
    this.successMessage = '';

    this.authService.updateUserStatus(user.id, newStatus).subscribe({
      next: (updatedUser: UserResponse) => {
        this.users = this.users.map(existingUser =>
          existingUser.id === updatedUser.id ? updatedUser : existingUser
        );

        this.statusUpdateLoadingId = null;
        this.successMessage =
          updatedUser.status === 'BLOCKED'
            ? `Le compte de ${updatedUser.firstName} ${updatedUser.lastName} a été bloqué.`
            : `Le compte de ${updatedUser.firstName} ${updatedUser.lastName} a été réactivé.`;
      },
      error: (error) => {
        console.error('Update status error:', error);
        this.statusUpdateLoadingId = null;
        this.errorMessage = 'Erreur lors de la modification du statut.';
      }
    });
  }

  deleteUser(user: UserResponse): void {
    const confirmation = confirm(
      `Voulez-vous vraiment supprimer l'utilisateur ${user.firstName} ${user.lastName} ? Cette action est définitive.`
    );

    if (!confirmation) {
      return;
    }

    this.deleteLoadingId = user.id;
    this.errorMessage = '';
    this.successMessage = '';

    this.authService.deleteUser(user.id).subscribe({
      next: () => {
        this.users = this.users.filter(existingUser => existingUser.id !== user.id);

        this.deleteLoadingId = null;
        this.successMessage = `L'utilisateur ${user.firstName} ${user.lastName} a été supprimé avec succès.`;
        this.smartSearch?.reload();
      },
      error: (error) => {
        console.error('Delete user error:', error);
        this.deleteLoadingId = null;
        this.errorMessage = 'Erreur lors de la suppression de l’utilisateur.';
      }
    });
  }

  getInitials(user: UserResponse): string {
    const first = user.firstName ? user.firstName.charAt(0).toUpperCase() : '';
    const last = user.lastName ? user.lastName.charAt(0).toUpperCase() : '';

    return `${first}${last}` || 'U';
  }

  formatDate(dateValue?: string): string {
    if (!dateValue || dateValue === '-') {
      return '-';
    }

    const date = new Date(dateValue);

    if (isNaN(date.getTime())) {
      return dateValue;
    }

    return date.toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    });
  }
}