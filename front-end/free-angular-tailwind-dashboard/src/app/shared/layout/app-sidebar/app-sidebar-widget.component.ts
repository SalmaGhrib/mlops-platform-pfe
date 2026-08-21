import { Component } from '@angular/core';
import { RouterModule } from '@angular/router';

@Component({
  selector: 'app-sidebar-widget',
  imports: [RouterModule],
  template: `
    <div class="ai-widget relative overflow-hidden rounded-2xl p-5">
      <div class="ai-grid absolute inset-0 opacity-40"></div>
      <div class="ai-blob absolute h-24 w-24 bg-brand-400/30" style="top: -30px; right: -20px"></div>

      <div class="relative z-10">
        <span class="inline-flex items-center gap-1.5 rounded-full bg-white/15 px-2.5 py-1 text-[10px] font-semibold uppercase tracking-wider text-white backdrop-blur">
          <svg class="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"/>
          </svg>
          Smart Retraining
        </span>

        <p class="mt-3 text-sm font-semibold text-white">
          Réentraînement intelligent
        </p>
        <p class="mt-1 text-xs leading-relaxed text-white/70">
          Décisions sécurisées : cooldown, détection de drift et blocage des données suspectes.
        </p>

        <a routerLink="/my-projects"
           class="mt-4 inline-flex items-center gap-1.5 rounded-lg bg-white/90 px-3 py-2 text-xs font-semibold text-brand-600 shadow-sm backdrop-blur transition hover:bg-white">
          Configurer
          <svg class="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/>
          </svg>
        </a>
      </div>
    </div>
  `
})
export class SidebarWidgetComponent {}
