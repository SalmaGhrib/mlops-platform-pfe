import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import matplotlib.patheffects as pe

fig, ax = plt.subplots(1, 1, figsize=(36, 24))
ax.set_xlim(-3, 27)
ax.set_ylim(-15, 5)
ax.set_aspect('equal')
ax.axis('off')
fig.patch.set_facecolor('white')

# Fonts
FONT_ACTOR = 20
FONT_MSG = 14
FONT_PHASE = 22
FONT_NOTE = 12
ARROW_LW = 3.0
ARROW_MUT = 25

BOX_FACE = '#D6EAF8'
BOX_EDGE = '#2471A3'
DASH_COLOR = '#ABB2B9'
TEXT_COLOR = '#1B2631'

# Actors
actors = [
    (0,  'Utilisateur'),
    (6,  'Frontend\nAngular'),
    (12, 'Backend\nSpring Boot'),
    (18, 'MySQL'),
    (24, 'Service\nEmail'),
]

for x, label in actors:
    rect = mpatches.FancyBboxPatch(
        (x - 2.5, -1.2), 5.0, 2.4,
        boxstyle="round,pad=0.3",
        facecolor=BOX_FACE, edgecolor=BOX_EDGE, linewidth=3.5,
        zorder=2
    )
    ax.add_patch(rect)
    ax.text(x, 0, label, ha='center', va='center', fontsize=FONT_ACTOR,
            fontweight='bold', color=TEXT_COLOR, zorder=3)
    ax.plot([x, x], [-1.2, -14.5], linestyle='--', color=DASH_COLOR,
            linewidth=1.5, zorder=0)

def msg(x1, y1, x2, y2, text, dashed=False):
    style = '--' if dashed else '-'
    ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                arrowprops=dict(arrowstyle='->', color='#2471A3',
                                linewidth=ARROW_LW, linestyle=style,
                                mutation_scale=ARROW_MUT), zorder=1)
    if text:
        mx, my = (x1 + x2) / 2, (y1 + y2) / 2
        ax.text(mx, my + 0.35, text, ha='center', va='bottom',
                fontsize=FONT_MSG, color=TEXT_COLOR, fontweight='bold',
                bbox=dict(boxstyle='round,pad=0.35', facecolor='#FEF9E7',
                          edgecolor='#F4D03F', linewidth=1, alpha=0.95),
                zorder=4)

def phase_box(x1, y1, x2, y2, color, label, label_color):
    rect = mpatches.FancyBboxPatch(
        (x1, y2), x2 - x1, y1 - y2,
        boxstyle="round,pad=0.3",
        facecolor=color, edgecolor=label_color,
        linewidth=2, linestyle='--', alpha=0.45, zorder=0
    )
    ax.add_patch(rect)
    ax.text(x1 + 0.4, y1 - 0.4, label, fontsize=FONT_PHASE,
            fontweight='bold', color=label_color, zorder=1)

# === Phase 1: Inscription ===
phase_box(0.3, -0.2, 25.2, -5.0, '#E8F5E9', '1. Inscription', '#1E8449')

y0 = -1.8
msg(0, y0, 6, y0, 'nom, email, mot de passe')
msg(6, y0, 12, y0, 'POST  /api/auth/signup')
msg(12, y0, 18, y0, 'INSERT  user')
msg(18, y0 - 1.0, 12, y0 - 1.0, '', dashed=True)
msg(12, y0 - 1.3, 24, y0 - 1.3, 'email verification')
msg(24, y0 - 2.3, 12, y0 - 2.3, '', dashed=True)
msg(12, y0 - 2.6, 6, y0 - 2.6, '201  Created', dashed=True)
msg(6, y0 - 3.5, 0, y0 - 3.5, 'compte cree', dashed=True)

# === Phase 2: Verification email ===
phase_box(0.3, -5.5, 25.2, -9.0, '#FEF5E7', '2. Verification email', '#CA6F1E')

y1 = -7.0
msg(0, y1, 6, y1, 'clique sur lien')
msg(6, y1, 12, y1, 'GET  /api/auth/verify')
msg(12, y1, 18, y1, 'UPDATE  verified')
msg(18, y1 - 1.0, 12, y1 - 1.0, '', dashed=True)
msg(12, y1 - 1.3, 6, y1 - 1.3, '200  OK', dashed=True)
msg(6, y1 - 2.2, 0, y1 - 2.2, 'email confirme', dashed=True)

# === Phase 3: Connexion ===
phase_box(0.3, -9.5, 25.2, -13.5, '#EBF5FB', '3. Connexion', '#2471A3')

y2 = -11.2
msg(0, y2, 6, y2, 'email + mot de passe')
msg(6, y2, 12, y2, 'POST  /api/auth/login')
msg(12, y2, 18, y2, 'SELECT  user')
msg(18, y2 - 1.0, 12, y2 - 1.0, '', dashed=True)
msg(12, y2 - 1.3, 6, y2 - 1.3, 'JWT  token', dashed=True)
msg(6, y2 - 2.2, 0, y2 - 2.2, 'token  +  redirect', dashed=True)

plt.tight_layout(pad=1)
plt.savefig('images/uml_auth_sequence.png', dpi=300, bbox_inches='tight',
            facecolor='white', edgecolor='none')
print("OK: images/uml_auth_sequence.png")
