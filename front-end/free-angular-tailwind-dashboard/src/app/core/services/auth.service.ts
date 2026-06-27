import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { Router } from '@angular/router';

export interface RegisterRequest {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  phone?: string;
  nationality?: string;
  bio?: string;
}

export interface AuthResponse {
  message: string;
  email: string;
  role: string;
}

export interface LoginRequest {
  email: string;
  password: string;
  rememberMe: boolean;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
  confirmPassword: string;
}

export interface LoginResponse {
  token: string;
  tokenType: string;
  userId: number;
  firstName: string;
  lastName: string;
  email: string;
  role: string;
}

export interface UserResponse {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  nationality: string;
  bio: string;
  role: string;
  status: string;
  emailVerified: boolean;
  createdAt: string;
}

export interface UpdateProfileRequest {
  firstName: string;
  lastName: string;
  phone: string;
  nationality: string;
  bio: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export interface UpdateUserRoleRequest {
  role: string;
}

export interface UpdateUserStatusRequest {
  status: string;
}

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private readonly authApiUrl = 'http://localhost:8081/api/auth';
  private readonly usersApiUrl = 'http://localhost:8081/api/users';
  private readonly adminUsersApiUrl = 'http://localhost:8081/api/admin/users';

  private readonly TOKEN_KEY = 'mlops_token';
  private readonly USER_KEY = 'mlops_user';
  private readonly ROLE_KEY = 'mlops_user_role';

  private authStateSubject = new BehaviorSubject<boolean>(this.isLoggedIn());
  authState$ = this.authStateSubject.asObservable();

  private roleSubject = new BehaviorSubject<string | null>(this.getUserRole());
  role$ = this.roleSubject.asObservable();

  constructor(
    private http: HttpClient,
    private router: Router
  ) {}

  register(data: RegisterRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.authApiUrl}/register`, data);
  }

  login(data: LoginRequest): Observable<LoginResponse> {
    this.clearAuthStorage();

    return this.http.post<LoginResponse>(`${this.authApiUrl}/login`, data).pipe(
      tap((response) => {
        this.saveSession(response, data.rememberMe);
      })
    );
  }

  private saveSession(response: LoginResponse, rememberMe: boolean): void {
    if (!response || !response.token) {
      return;
    }

    const normalizedRole = this.normalizeRole(response.role);

    const user = {
      id: response.userId,
      firstName: response.firstName,
      lastName: response.lastName,
      email: response.email,
      role: normalizedRole,
    };

    const storage = rememberMe ? localStorage : sessionStorage;

    storage.setItem(this.TOKEN_KEY, response.token);
    storage.setItem(this.ROLE_KEY, normalizedRole);
    storage.setItem(this.USER_KEY, JSON.stringify(user));

    this.authStateSubject.next(true);
    this.roleSubject.next(normalizedRole);
  }

  verifyEmail(token: string): Observable<{ message: string }> {
    return this.http.get<{ message: string }>(
      `${this.authApiUrl}/verify-email?token=${token}`
    );
  }

  saveToken(token: string, rememberMe: boolean): void {
    this.removeKeyFromBothStorages(this.TOKEN_KEY);

    if (rememberMe) {
      localStorage.setItem(this.TOKEN_KEY, token);
    } else {
      sessionStorage.setItem(this.TOKEN_KEY, token);
    }

    this.authStateSubject.next(true);
  }

  getToken(): string | null {
    return (
      localStorage.getItem(this.TOKEN_KEY) ||
      sessionStorage.getItem(this.TOKEN_KEY)
    );
  }

  isLoggedIn(): boolean {
    return !!this.getToken();
  }

  saveUserRole(role: string, rememberMe: boolean): void {
    const normalizedRole = this.normalizeRole(role);

    this.removeKeyFromBothStorages(this.ROLE_KEY);

    if (rememberMe) {
      localStorage.setItem(this.ROLE_KEY, normalizedRole);
    } else {
      sessionStorage.setItem(this.ROLE_KEY, normalizedRole);
    }

    this.roleSubject.next(normalizedRole);
  }

  getUserRole(): string | null {
    const role =
      localStorage.getItem(this.ROLE_KEY) ||
      sessionStorage.getItem(this.ROLE_KEY);

    if (role) {
      return this.normalizeRole(role);
    }

    const currentUser = this.getStoredUser();

    if (currentUser?.role) {
      return this.normalizeRole(currentUser.role);
    }

    return null;
  }

  isAdmin(): boolean {
    const role = this.getUserRole();
    return role === 'ADMIN' || role === 'SUPER_ADMIN';
  }

  isSimpleUser(): boolean {
    const role = this.getUserRole();
    return role === 'USER';
  }

  getStoredUser(): any | null {
    const raw =
      localStorage.getItem(this.USER_KEY) ||
      sessionStorage.getItem(this.USER_KEY);

    if (!raw) {
      return null;
    }

    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }

  getCurrentUser(): Observable<UserResponse> {
    return this.http.get<UserResponse>(`${this.authApiUrl}/me`, {
      headers: this.getAuthHeaders(),
    }).pipe(
      tap((user) => {
        const hasLocalToken = !!localStorage.getItem(this.TOKEN_KEY);
        const storage = hasLocalToken ? localStorage : sessionStorage;

        const normalizedRole = this.normalizeRole(user.role);

        storage.setItem(this.USER_KEY, JSON.stringify({
          ...user,
          role: normalizedRole,
        }));

        storage.setItem(this.ROLE_KEY, normalizedRole);

        this.authStateSubject.next(true);
        this.roleSubject.next(normalizedRole);
      })
    );
  }

  updateProfile(data: UpdateProfileRequest): Observable<UserResponse> {
    return this.http.put<UserResponse>(
      `${this.usersApiUrl}/me`,
      data,
      {
        headers: this.getAuthHeaders(),
      }
    ).pipe(
      tap((user) => {
        const hasLocalToken = !!localStorage.getItem(this.TOKEN_KEY);
        const storage = hasLocalToken ? localStorage : sessionStorage;

        const normalizedRole = this.normalizeRole(user.role);

        storage.setItem(this.USER_KEY, JSON.stringify({
          ...user,
          role: normalizedRole,
        }));

        storage.setItem(this.ROLE_KEY, normalizedRole);

        this.roleSubject.next(normalizedRole);
      })
    );
  }

  changePassword(data: ChangePasswordRequest): Observable<{ message: string }> {
    return this.http.put<{ message: string }>(
      `${this.usersApiUrl}/me/password`,
      data,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  getAllUsers(): Observable<UserResponse[]> {
    return this.http.get<UserResponse[]>(
      this.adminUsersApiUrl,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  updateUserRole(userId: number, role: string): Observable<UserResponse> {
    return this.http.put<UserResponse>(
      `${this.adminUsersApiUrl}/${userId}/role`,
      { role },
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  updateUserStatus(userId: number, status: string): Observable<UserResponse> {
    return this.http.put<UserResponse>(
      `${this.adminUsersApiUrl}/${userId}/status`,
      { status },
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  deleteUser(userId: number): Observable<{ message: string }> {
    return this.http.delete<{ message: string }>(
      `${this.adminUsersApiUrl}/${userId}`,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  forgotPassword(data: ForgotPasswordRequest): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(
      `${this.authApiUrl}/forgot-password`,
      data
    );
  }

  resetPassword(data: ResetPasswordRequest): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(
      `${this.authApiUrl}/reset-password`,
      data
    );
  }

  logout(): void {
    this.clearAuthStorage();

    this.authStateSubject.next(false);
    this.roleSubject.next(null);

    this.router.navigate(['/signin']);
  }

  private getAuthHeaders(): HttpHeaders {
    const token = this.getToken();

    return new HttpHeaders({
      Authorization: token ? `Bearer ${token}` : '',
    });
  }

  private normalizeRole(role: string | null | undefined): string {
    if (!role) {
      return '';
    }

    return role
      .toString()
      .trim()
      .replace(/^ROLE_/i, '')
      .replace('-', '_')
      .toUpperCase();
  }

  private clearAuthStorage(): void {
    this.removeKeyFromBothStorages(this.TOKEN_KEY);
    this.removeKeyFromBothStorages(this.USER_KEY);
    this.removeKeyFromBothStorages(this.ROLE_KEY);

    /*
     * Anciennes clés utilisées avant.
     * On les supprime aussi pour éviter que l'ancien compte ADMIN reste actif.
     */
    this.removeKeyFromBothStorages('token');
    this.removeKeyFromBothStorages('user');
    this.removeKeyFromBothStorages('currentUser');
    this.removeKeyFromBothStorages('userRole');
    this.removeKeyFromBothStorages('role');
  }

  private removeKeyFromBothStorages(key: string): void {
    localStorage.removeItem(key);
    sessionStorage.removeItem(key);
  }
}