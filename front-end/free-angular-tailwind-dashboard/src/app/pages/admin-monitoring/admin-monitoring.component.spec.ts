import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AdminMonitoringComponent } from './admin-monitoring.component';

describe('AdminMonitoringComponent', () => {
  let component: AdminMonitoringComponent;
  let fixture: ComponentFixture<AdminMonitoringComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminMonitoringComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(AdminMonitoringComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
