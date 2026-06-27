import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';

import { CheckboxComponent } from '../../form/input/checkbox.component';
import { AuthService, LoginResponse } from '../../../../core/services/auth.service';

@Component({
  selector: 'app-signin-form',
  standalone: true,
  imports: [
    CommonModule,
    CheckboxComponent,
    RouterModule,
    FormsModule
  ],
  templateUrl: './signin-form.component.html',
  styles: ``
})
export class SigninFormComponent {
  showPassword = false;
  isChecked = false;

  email = '';
  password = '';

  loading = false;
  errorMessage = '';

  constructor(
    private authService: AuthService,
    private router: Router
  ) {}

  togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  onSignIn(): void {
    this.errorMessage = '';

    const email = this.email.trim();
    const password = this.password.trim();

    if (!email || !password) {
      this.errorMessage = 'Veuillez saisir votre email et votre mot de passe.';
      return;
    }

    this.loading = true;

    this.authService.login({
      email: email,
      password: password,
      rememberMe: this.isChecked
    }).subscribe({
      next: (response: LoginResponse) => {
        this.authService.saveToken(response.token, this.isChecked);
        this.authService.saveUserRole(response.role, this.isChecked);

        this.loading = false;
        this.router.navigate(['/']);
      },
      error: (error) => {
        console.error('Login error:', error);

        this.loading = false;

        const message = error?.error?.message || '';

        if (message.includes('Account is not active')) {
          this.errorMessage = 'Votre compte est désactivé. Veuillez contacter l’administrateur.';
        } else {
          this.errorMessage = 'Email ou mot de passe incorrect.';
        }
      }
    });
  }
}