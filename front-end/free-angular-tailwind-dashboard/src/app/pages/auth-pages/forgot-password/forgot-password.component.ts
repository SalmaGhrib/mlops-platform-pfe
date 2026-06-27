import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule
  ],
  templateUrl: './forgot-password.component.html',
  styles: ``
})
export class ForgotPasswordComponent {
  email = '';

  loading = false;
  successMessage = '';
  errorMessage = '';

  constructor(private authService: AuthService) {}

  onSubmit(): void {
    this.successMessage = '';
    this.errorMessage = '';

    if (!this.email.trim()) {
      this.errorMessage = 'Veuillez saisir votre adresse email.';
      return;
    }

    this.loading = true;

    this.authService.forgotPassword({
      email: this.email.trim()
    }).subscribe({
      next: (response: { message: string }) => {
        this.loading = false;
        this.successMessage =
          response.message || 'Si cet email existe, un lien de réinitialisation a été envoyé.';
      },
      error: (error: HttpErrorResponse) => {
        console.error('Forgot password error:', error);
        this.loading = false;
        this.errorMessage = 'Impossible d’envoyer le lien de réinitialisation.';
      }
    });
  }
}