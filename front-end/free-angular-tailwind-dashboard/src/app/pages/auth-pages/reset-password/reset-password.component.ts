import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule
  ],
  templateUrl: './reset-password.component.html',
  styles: ``
})
export class ResetPasswordComponent implements OnInit {
  token = '';

  newPassword = '';
  confirmPassword = '';

  loading = false;
  successMessage = '';
  errorMessage = '';

  constructor(
    private route: ActivatedRoute,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    const tokenFromUrl = this.route.snapshot.queryParamMap.get('token');

    if (!tokenFromUrl) {
      this.errorMessage = 'Token de réinitialisation manquant.';
      return;
    }

    this.token = tokenFromUrl;
  }

  onSubmit(): void {
    this.successMessage = '';
    this.errorMessage = '';

    if (!this.newPassword || !this.confirmPassword) {
      this.errorMessage = 'Veuillez remplir tous les champs.';
      return;
    }

    if (this.newPassword.length < 6) {
      this.errorMessage = 'Le mot de passe doit contenir au moins 6 caractères.';
      return;
    }

    if (this.newPassword !== this.confirmPassword) {
      this.errorMessage = 'Les mots de passe ne correspondent pas.';
      return;
    }

    this.loading = true;

    this.authService.resetPassword({
      token: this.token,
      newPassword: this.newPassword,
      confirmPassword: this.confirmPassword
    }).subscribe({
      next: (response: { message: string }) => {
        this.loading = false;
        this.successMessage =
          response.message || 'Mot de passe réinitialisé avec succès.';
      },
      error: (error: HttpErrorResponse) => {
        console.error('Reset password error:', error);
        this.loading = false;

        const message = error?.error?.message || '';

        if (message.includes('expired')) {
          this.errorMessage = 'Le lien de réinitialisation a expiré.';
        } else if (message.includes('Invalid')) {
          this.errorMessage = 'Le lien de réinitialisation est invalide.';
        } else if (message.includes('do not match')) {
          this.errorMessage = 'Les mots de passe ne correspondent pas.';
        } else {
          this.errorMessage = 'Impossible de réinitialiser le mot de passe.';
        }
      }
    });
  }
}