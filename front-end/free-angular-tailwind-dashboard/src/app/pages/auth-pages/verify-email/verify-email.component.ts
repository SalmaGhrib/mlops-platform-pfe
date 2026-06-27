import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-verify-email',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule
  ],
  templateUrl: './verify-email.component.html',
  styles: ``
})
export class VerifyEmailComponent implements OnInit {
  loading = true;
  successMessage = '';
  errorMessage = '';

  constructor(
    private route: ActivatedRoute,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');

    if (!token) {
      this.loading = false;
      this.errorMessage = 'Token de vérification manquant.';
      return;
    }

    this.authService.verifyEmail(token).subscribe({
      next: (response: { message: string }) => {
        this.loading = false;
        this.successMessage = response.message || 'Votre email a été vérifié avec succès.';
      },
      error: (error: HttpErrorResponse) => {
        console.error('Email verification error:', error);
        this.loading = false;

        const message = error?.error?.message || '';

        if (message.includes('expired')) {
          this.errorMessage = 'Le lien de vérification a expiré.';
        } else if (message.includes('Invalid')) {
          this.errorMessage = 'Le lien de vérification est invalide.';
        } else {
          this.errorMessage = 'Impossible de vérifier votre email.';
        }
      }
    });
  }
}