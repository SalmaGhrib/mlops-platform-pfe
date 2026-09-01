import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

fig, ax = plt.subplots(1, 1, figsize=(42, 28))
ax.set_xlim(-3, 34)
ax.set_ylim(-18, 5)
ax.set_aspect('equal')
ax.axis('off')
fig.patch.set_facecolor('white')

FONT_ACTOR = 18
FONT_MSG = 13
FONT_PHASE = 20
ARROW_LW = 3.0
ARROW_MUT = 25

BOX_FACE = '#D6EAF8'
BOX_EDGE = '#2471A3'
DASH_COLOR = '#ABB2B9'
TEXT_COLOR = '#1B2631'

actors = [
    (0,  'Utilisateur'),
    (5,  'Frontend'),
    (10, 'Backend'),
    (15, 'Kaniko'),
    (20, 'ACR'),
    (25, 'Job K8s'),
    (30, 'MLflow'),
]

for x, label in actors:
    rect = mpatches.FancyBboxPatch(
        (x - 2.3, -1.2), 4.6, 2.4,
        boxstyle="round,pad=0.3",
        facecolor=BOX_FACE, edgecolor=BOX_EDGE, linewidth=3.5, zorder=2
    )
    ax.add_patch(rect)
    ax.text(x, 0, label, ha='center', va='center', fontsize=FONT_ACTOR,
            fontweight='bold', color=TEXT_COLOR, zorder=3)
    ax.plot([x, x], [-1.2, -17.5], linestyle='--', color=DASH_COLOR,
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

# Phase 1
phase_box(0.3, -0.2, 32.2, -3.5, '#E8F5E9', '1. Envoi des fichiers', '#1E8449')
y0 = -1.8
msg(0, y0, 5, y0, 'script + dataset + Dockerfile + requirements')
msg(5, y0, 10, y0, 'POST  /api/custom-run')

# Phase 2
phase_box(0.3, -4.0, 32.2, -6.8, '#FEF5E7', '2. Preparation', '#CA6F1E')
y1 = -5.2
msg(10, y1, 10, y1 + 0.8, '', dashed=True)
ax.text(10.5, y1 + 0.2, 'Injection code platform\ndans Dockerfile', fontsize=FONT_MSG,
        color='#CA6F1E', fontweight='bold', zorder=4)
msg(10, y1 - 0.6, 10, y1 - 0.6, '', dashed=False)
ax.text(10.5, y1 - 1.0, 'Preparation workspace\n+ ConfigMap', fontsize=FONT_MSG,
        color='#CA6F1E', fontweight='bold', zorder=4)

# Phase 3
phase_box(0.3, -7.3, 32.2, -11.5, '#FDEDEC', '3. Job 1 : Build (Kaniko)', '#922B21')
y2 = -9.0
msg(10, y2, 15, y2, 'Creation Job build')
msg(15, y2 - 0.8, 15, y2 - 0.8, '', dashed=False)
ax.text(15.5, y2 - 1.3, 'docker build\n(Dockerfile modifie)', fontsize=FONT_MSG,
        color='#922B21', fontweight='bold', zorder=4)
msg(15, y2 - 1.8, 20, y2 - 1.8, 'docker push')
msg(20, y2 - 2.6, 15, y2 - 2.6, '', dashed=True)
msg(15, y2 - 2.9, 10, y2 - 2.9, 'image tag', dashed=True)

# Phase 4
phase_box(0.3, -12.0, 32.2, -16.0, '#EBF5FB', '4. Job 2 : Entrainement', '#2471A3')
y3 = -13.5
msg(10, y3, 25, y3, 'Creation Job training')
msg(25, y3 - 0.8, 20, y3 - 0.8, 'pull image', dashed=True)
msg(20, y3 - 1.2, 25, y3 - 1.2, '', dashed=True)
ax.text(25.5, y3 - 1.0, 'Montage PVC\n(dataset + scripts)', fontsize=FONT_MSG,
        color='#2471A3', fontweight='bold', zorder=4)
msg(25, y3 - 1.8, 25, y3 - 1.8, '', dashed=False)
ax.text(25.5, y3 - 2.3, 'Execution\nplatform_runner.py', fontsize=FONT_MSG,
        color='#2471A3', fontweight='bold', zorder=4)
msg(25, y3 - 2.8, 30, y3 - 2.8, 'log_metric + log_artifact')
msg(30, y3 - 3.4, 25, y3 - 3.4, '', dashed=True)
msg(25, y3 - 3.7, 30, y3 - 3.7, 'register_model')
msg(30, y3 - 4.2, 25, y3 - 4.2, '', dashed=True)

# Phase 5
phase_box(0.3, -16.5, 32.2, -18.5, '#F4ECF7', '5. Resultats', '#7D3C98')
y4 = -17.5
msg(25, y4, 10, y4, 'status', dashed=True)
msg(10, y4 - 0.5, 5, y4 - 0.5, 'resultats', dashed=True)
msg(5, y4 - 1.0, 0, y4 - 1.0, 'notification WebSocket', dashed=True)

plt.tight_layout(pad=1)
plt.savefig('images/uml_custom_sequence.png', dpi=300, bbox_inches='tight',
            facecolor='white', edgecolor='none')
print("OK: images/uml_custom_sequence.png")
