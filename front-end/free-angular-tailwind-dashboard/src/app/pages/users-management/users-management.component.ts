import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService, UserResponse } from '../../core/services/auth.service';
import { PageBreadcrumbComponent } from '../../shared/components/common/page-breadcrumb/page-breadcrumb.component';

@Component({
  selector: 'app-users-management',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent
  ],
  templateUrl: './users-management.component.html',
  styles: ``
})
export class UsersManagementComponent implements OnInit {
  users: UserResponse[] = [];

  loading = false;
  errorMessage = '';
  successMessage = '';

  roleUpdateLoadingId: number | null = null;
  statusUpdateLoadingId: number | null = null;
  deleteLoadingId: number | null = null;

  constructor(private authService: AuthService) {}

  ngOnInit(): void {
    this.loadUsers();
  }

  loadUsers(): void {
    this.loading = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.authService.getAllUsers().subscribe({
      next: (response: UserResponse[]) => {
        this.users = response;
        this.loading = false;
      },
      error: (error) => {
        console.error('Load users error:', error);
        this.loading = false;

        if (error.status === 401 || error.status === 403) {
          this.errorMessage = 'Accès refusé. Vous devez être connecté avec un compte ADMIN.';
        } else if (error.status === 0) {
          this.errorMessage = 'Impossible de contacter le backend. Vérifiez que Spring Boot est lancé.';
        } else {
          this.errorMessage = 'Impossible de charger la liste des utilisateurs.';
        }
      }
    });
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