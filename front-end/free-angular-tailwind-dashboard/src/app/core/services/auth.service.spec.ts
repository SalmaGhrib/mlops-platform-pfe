import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { Router } from '@angular/router';

import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        AuthService,
        {
          provide: Router,
          useValue: { navigate: jasmine.createSpy('navigate') }
        }
      ]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);

    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should send rememberMe in login payload', () => {
    const response = {
      token: 'jwt-token',
      userId: 1,
      firstName: 'Jane',
      lastName: 'Doe',
      email: 'jane@example.com',
      role: 'ADMIN'
    };

    service.login({ email: 'jane@example.com', password: 'secret', rememberMe: true })
      .subscribe((res) => {
        expect(res.token).toBe('jwt-token');
      });

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      email: 'jane@example.com',
      password: 'secret',
      rememberMe: true
    });

    req.flush(response);

    expect(localStorage.getItem('mlops_token')).toBe('jwt-token');
    expect(localStorage.getItem('mlops_expires_at')).toBeTruthy();
    expect(sessionStorage.getItem('mlops_token')).toBeNull();
  });

  it('should store session in sessionStorage when rememberMe is false', () => {
    const response = {
      token: 'jwt-token',
      userId: 1,
      firstName: 'Jane',
      lastName: 'Doe',
      email: 'jane@example.com',
      role: 'USER'
    };

    service.login({ email: 'jane@example.com', password: 'secret', rememberMe: false })
      .subscribe();

    const req = httpMock.expectOne('/api/auth/login');
    req.flush(response);

    expect(sessionStorage.getItem('mlops_token')).toBe('jwt-token');
    expect(sessionStorage.getItem('mlops_expires_at')).toBeTruthy();
    expect(localStorage.getItem('mlops_token')).toBeNull();
  });

  it('should clear both storages on logout', () => {
    localStorage.setItem('mlops_token', 'jwt-token');
    localStorage.setItem('mlops_user', JSON.stringify({ id: 1 }));
    localStorage.setItem('mlops_user_role', 'ADMIN');
    localStorage.setItem('mlops_expires_at', '2099-01-01T00:00:00.000Z');

    service.logout();

    expect(localStorage.getItem('mlops_token')).toBeNull();
    expect(localStorage.getItem('mlops_user')).toBeNull();
    expect(localStorage.getItem('mlops_user_role')).toBeNull();
    expect(localStorage.getItem('mlops_expires_at')).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/signin']);
  });

  it('should report not logged in when session expired', () => {
    localStorage.setItem('mlops_token', 'jwt-token');
    localStorage.setItem('mlops_expires_at', '2000-01-01T00:00:00.000Z');

    expect(service.isLoggedIn()).toBe(false);
    expect(localStorage.getItem('mlops_token')).toBeNull();
  });
});