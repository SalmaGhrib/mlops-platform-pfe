import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';

import { AuthService } from '../../../../core/services/auth.service';

@Component({
  selector: 'app-signup-form',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    FormsModule
  ],
  templateUrl: './signup-form.component.html',
  styles: ``
})
export class SignupFormComponent {
  showPassword = false;
  isChecked = false;

  fname = '';
  lname = '';
  email = '';
  password = '';

  loading = false;
  errorMessage = '';
  successMessage = '';

  constructor(
    private authService: AuthService,
    private router: Router
  ) {}

  togglePasswordVisibility() {
    this.showPassword = !this.showPassword;
  }

  onSignUp() {
    this.errorMessage = '';
    this.successMessage = '';

    if (!this.fname || !this.lname || !this.email || !this.password) {
      this.errorMessage = 'Veuillez remplir tous les champs obligatoires.';
      return;
    }

    if (!this.isChecked) {
      this.errorMessage = 'Veuillez accepter les conditions générales.';
      return;
    }

    this.loading = true;

    this.authService.register({
      firstName: this.fname,
      lastName: this.lname,
      email: this.email,
      password: this.password,
      phone: '',
      nationality: 'Tunisian',
      bio: ''
    }).subscribe({
      next: () => {
        this.loading = false;
        this.successMessage = 'Compte créé avec succès. Redirection vers la connexion...';

        setTimeout(() => {
          this.router.navigate(['/signin']);
        }, 1200);
      },
      error: (error) => {
        console.error('Register error:', error);
        this.loading = false;

        if (error?.error?.message?.includes('Email already exists')) {
          this.errorMessage = 'Cet email est déjà utilisé.';
        } else {
          this.errorMessage = 'Erreur lors de la création du compte.';
        }
      }
    });
  }
}