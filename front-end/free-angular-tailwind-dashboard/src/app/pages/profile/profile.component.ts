import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PageBreadcrumbComponent } from '../../shared/components/common/page-breadcrumb/page-breadcrumb.component';
import { ModalComponent } from '../../shared/components/ui/modal/modal.component';

import {
  AuthService,
  UserResponse,
  UpdateProfileRequest,
  ChangePasswordRequest
} from '../../core/services/auth.service';

interface UserProfile {
  avatar: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  nationality: string;
  role: string;
  bio: string;
  accountStatus: string;
  createdAt: string;
  emailVerified: boolean;
}

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    PageBreadcrumbComponent,
    ModalComponent
  ],
  templateUrl: './profile.component.html',
  styles: ``
})
export class ProfileComponent implements OnInit {
  loading = false;
  errorMessage = '';

  user: UserProfile = {
    avatar: '/images/user/user-01.png',
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    nationality: '',
    role: '',
    bio: '',
    accountStatus: '',
    createdAt: '-',
    emailVerified: false
  };

  isEditModalOpen = false;
  editForm: UserProfile = { ...this.user };

  isPasswordModalOpen = false;

  passwordForm = {
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  };

  passwordLoading = false;
  passwordErrorMessage = '';
  passwordSuccessMessage = '';

  constructor(private authService: AuthService) {}

  ngOnInit(): void {
    this.loadCurrentUser();
  }

  loadCurrentUser(): void {
    this.loading = true;
    this.errorMessage = '';

    this.authService.getCurrentUser().subscribe({
      next: (response: UserResponse) => {
        this.user = {
          avatar: '/images/user/user-01.png',
          firstName: response.firstName || '',
          lastName: response.lastName || '',
          email: response.email || '',
          phone: response.phone || '',
          nationality: response.nationality || '',
          role: response.role || '',
          bio: response.bio || '',
          accountStatus: response.status || '',
          createdAt: this.formatDate(response.createdAt),
          emailVerified: response.emailVerified
        };

        this.editForm = { ...this.user };
        this.loading = false;
      },
      error: (error) => {
        console.error('Profile loading error:', error);
        this.loading = false;
        this.errorMessage = 'Impossible de charger les informations du profil.';
      }
    });
  }

  formatDate(dateValue?: string): string {
    if (!dateValue) {
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

  editProfile(): void {
    this.editForm = { ...this.user };
    this.isEditModalOpen = true;
  }

  closeEditModal(): void {
    this.isEditModalOpen = false;
  }

  saveProfile(): void {
    if (
      !this.editForm.firstName.trim() ||
      !this.editForm.lastName.trim()
    ) {
      alert('Veuillez remplir les champs obligatoires.');
      return;
    }

    const request: UpdateProfileRequest = {
      firstName: this.editForm.firstName,
      lastName: this.editForm.lastName,
      phone: this.editForm.phone,
      nationality: this.editForm.nationality,
      bio: this.editForm.bio
    };

    this.loading = true;

    this.authService.updateProfile(request).subscribe({
      next: (response: UserResponse) => {
        this.user = {
          ...this.user,
          firstName: response.firstName || '',
          lastName: response.lastName || '',
          email: response.email || '',
          phone: response.phone || '',
          nationality: response.nationality || '',
          role: response.role || '',
          bio: response.bio || '',
          accountStatus: response.status || '',
          createdAt: this.formatDate(response.createdAt),
          emailVerified: response.emailVerified
        };

        this.editForm = { ...this.user };
        this.loading = false;
        this.isEditModalOpen = false;
      },
      error: (error) => {
        console.error('Update profile error:', error);
        this.loading = false;
        alert('Erreur lors de la mise à jour du profil.');
      }
    });
  }

  openPasswordModal(): void {
    this.passwordForm = {
      currentPassword: '',
      newPassword: '',
      confirmPassword: ''
    };

    this.passwordErrorMessage = '';
    this.passwordSuccessMessage = '';
    this.isPasswordModalOpen = true;
  }

  closePasswordModal(): void {
    this.isPasswordModalOpen = false;
  }

  changePassword(): void {
    this.passwordErrorMessage = '';
    this.passwordSuccessMessage = '';

    if (
      !this.passwordForm.currentPassword.trim() ||
      !this.passwordForm.newPassword.trim() ||
      !this.passwordForm.confirmPassword.trim()
    ) {
      this.passwordErrorMessage = 'Veuillez remplir tous les champs.';
      return;
    }

    if (this.passwordForm.newPassword.length < 6) {
      this.passwordErrorMessage = 'Le nouveau mot de passe doit contenir au moins 6 caractères.';
      return;
    }

    if (this.passwordForm.newPassword !== this.passwordForm.confirmPassword) {
      this.passwordErrorMessage = 'Le nouveau mot de passe et la confirmation ne correspondent pas.';
      return;
    }

    const request: ChangePasswordRequest = {
      currentPassword: this.passwordForm.currentPassword,
      newPassword: this.passwordForm.newPassword,
      confirmPassword: this.passwordForm.confirmPassword
    };

    this.passwordLoading = true;

    this.authService.changePassword(request).subscribe({
      next: () => {
        this.passwordLoading = false;
        this.passwordSuccessMessage = 'Mot de passe modifié avec succès.';

        setTimeout(() => {
          this.closePasswordModal();
        }, 1200);
      },
      error: (error) => {
        console.error('Change password error:', error);
        this.passwordLoading = false;

        const message = error?.error?.message || '';

        if (message.includes('Current password is incorrect')) {
          this.passwordErrorMessage = 'Le mot de passe actuel est incorrect.';
        } else if (message.includes('do not match')) {
          this.passwordErrorMessage = 'Le nouveau mot de passe et la confirmation ne correspondent pas.';
        } else if (message.includes('different from current password')) {
          this.passwordErrorMessage = 'Le nouveau mot de passe doit être différent de l’ancien.';
        } else {
          this.passwordErrorMessage = 'Erreur lors de la modification du mot de passe.';
        }
      }
    });
  }
}