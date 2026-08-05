import { Component, OnInit } from '@angular/core';
import { DropdownComponent } from '../../ui/dropdown/dropdown.component';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { DropdownItemTwoComponent } from '../../ui/dropdown/dropdown-item/dropdown-item.component-two';
import { AuthService, UserResponse } from '../../../../core/services/auth.service';

@Component({
  selector: 'app-user-dropdown',
  standalone: true,
  templateUrl: './user-dropdown.component.html',
  imports: [
    CommonModule,
    RouterModule,
    DropdownComponent,
    DropdownItemTwoComponent
  ]
})
export class UserDropdownComponent implements OnInit {
  isOpen = false;

  firstName = '';
  lastName = '';
  email = '';
  role = '';

  constructor(
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadCurrentUser();
  }

  loadCurrentUser(): void {
    this.authService.getCurrentUser().subscribe({
      next: (user: UserResponse) => {
        this.firstName = user.firstName || '';
        this.lastName = user.lastName || '';
        this.email = user.email || '';
        this.role = user.role || '';
      },
      error: (error) => {
        console.error('User dropdown loading error:', error);
      }
    });
  }

  get displayName(): string {
    const fullName = `${this.firstName} ${this.lastName}`.trim();
    return fullName || 'Utilisateur';
  }

  get initials(): string {
    const first = (this.firstName || '').trim().charAt(0);
    const last = (this.lastName || '').trim().charAt(0);
    return `${first}${last}`.toUpperCase() || 'U';
  }

  toggleDropdown(): void {
    this.isOpen = !this.isOpen;
  }

  closeDropdown(): void {
    this.isOpen = false;
  }

  logout(): void {
    this.authService.logout();
    this.closeDropdown();
    this.router.navigate(['/signin']);
  }
}