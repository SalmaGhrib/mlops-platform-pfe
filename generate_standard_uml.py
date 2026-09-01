import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

fig, ax = plt.subplots(1, 1, figsize=(36, 24))
ax.set_xlim(-3, 28)
ax.set_ylim(-15, 5)
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
    (15, 'Pipeline\nGateway'),
    (20, 'Kubeflow'),
    (25, 'MLflow'),
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

# Phase 1
phase_box(0.3, -0.2, 26.2, -3.8, '#E8F5E9', '1. Envoi des fichiers', '#1E8449')
y0 = -1.8
msg(0, y0, 5, y0, 'script.py + dataset')
msg(5, y0, 10, y0, 'POST  /api/run')
msg(10, y0, 15, y0, 'POST  /upload-and-run')

# Phase 2
phase_box(0.3, -4.3, 26.2, -8.0, '#FEF5E7', '2. Analyse AST + Soumission pipeline', '#CA6F1E')
y1 = -5.8
msg(15, y1, 15, y1 + 0.8, '', dashed=True)
ax.text(15.5, y1 + 0.3, 'Analyse imports\n+ algorithmes', fontsize=FONT_MSG,
        color='#CA6F1E', fontweight='bold', zorder=4)
msg(10, y1 - 0.5, 15, y1 - 0.5, 'script + dataset')
msg(15, y1 - 1.2, 20, y1 - 1.2, 'kfp.create_run()')
ax.text(17.5, y1 - 0.9, 'Generation YAML', fontsize=FONT_MSG,
        color='#CA6F1E', fontweight='bold', fontstyle='italic', zorder=4)

# Phase 3
phase_box(0.3, -8.5, 26.2, -12.5, '#EBF5FB', '3. Execution pipeline', '#2471A3')
y2 = -10.0
msg(20, y2, 15, y2, 'telecharge dataset', dashed=True)
msg(20, y2 - 0.8, 20, y2 - 0.8, '', dashed=False)
ax.text(20.5, y2 - 1.2, 'Execution script\n(image standard)', fontsize=FONT_MSG,
        color='#2471A3', fontweight='bold', zorder=4)
msg(20, y2 - 1.6, 25, y2 - 1.6, 'log_param, log_metric')
msg(25, y2 - 2.2, 20, y2 - 2.2, '', dashed=True)
msg(20, y2 - 2.5, 25, y2 - 2.5, 'log_artifact, register_model')
msg(25, y2 - 3.0, 20, y2 - 3.0, '', dashed=True)

# Phase 4
phase_box(0.3, -13.0, 26.2, -15.0, '#F4ECF7', '4. Resultats', '#7D3C98')
y3 = -14.0
msg(20, y3, 15, y3, 'run_id + status', dashed=True)
msg(15, y3 - 0.5, 10, y3 - 0.5, 'status', dashed=True)
msg(10, y3 - 1.0, 25, y3 - 1.0, 'GET metrics + score')
msg(25, y3 - 1.5, 10, y3 - 1.5, '', dashed=True)
msg(10, y3 - 1.8, 5, y3 - 1.8, 'metriques + score global', dashed=True)
msg(5, y3 - 2.2, 0, y3 - 2.2, 'resultats affiches', dashed=True)

plt.tight_layout(pad=1)
plt.savefig('images/uml_standard_sequence.png', dpi=300, bbox_inches='tight',
            facecolor='white', edgecolor='none')
print("OK: images/uml_standard_sequence.png")
