import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

fig, ax = plt.subplots(1, 1, figsize=(36, 24))
ax.set_xlim(-3, 28)
ax.set_ylim(-16, 5)
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
    (10, 'Backend\nSpring Boot'),
    (17, 'KubernetesModel\nServingService'),
    (23, 'Kubernetes\nCluster'),
]

for x, label in actors:
    rect = mpatches.FancyBboxPatch(
        (x - 2.5, -1.2), 5.0, 2.4,
        boxstyle="round,pad=0.3",
        facecolor=BOX_FACE, edgecolor=BOX_EDGE, linewidth=3.5, zorder=2
    )
    ax.add_patch(rect)
    ax.text(x, 0, label, ha='center', va='center', fontsize=FONT_ACTOR,
            fontweight='bold', color=TEXT_COLOR, zorder=3)
    ax.plot([x, x], [-1.2, -15.5], linestyle='--', color=DASH_COLOR,
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

# Phase 1: Demande de deploiement
phase_box(0.3, -0.2, 25.2, -3.5, '#E8F5E9', '1. Demande de deploiement', '#1E8449')
y0 = -1.6
msg(0, y0, 5, y0, 'declenche deploiement')
msg(5, y0, 10, y0, 'POST /api/deployments/deploy')
msg(10, y0, 17, y0, 'deployProductionModel()')

# Phase 2: Preparation
phase_box(0.3, -4.0, 25.2, -7.0, '#FEF5E7', '2. Preparation du déploiement', '#CA6F1E')
y1 = -5.2
msg(10, y1, 10, y1 + 0.7, '', dashed=True)
ax.text(10.5, y1 + 0.2, 'Resolution nom modele\n+ version', fontsize=FONT_MSG,
        color='#CA6F1E', fontweight='bold', zorder=4)
msg(10, y1 - 0.5, 10, y1 - 0.5, '', dashed=False)
ax.text(10.5, y1 - 0.9, 'Selection image\nserving (standard/custom)', fontsize=FONT_MSG,
        color='#CA6F1E', fontweight='bold', zorder=4)

# Phase 3: Deploy sur track inactif
phase_box(0.3, -7.5, 25.2, -10.5, '#EBF5FB', '3. Déploiement sur track inactif (green)', '#2471A3')
y2 = -8.8
msg(10, y2, 17, y2, 'deployModelServer(track=green)')
msg(17, y2, 23, y2, 'Creer Deployment + Service')
ax.text(23.5, y2 + 0.4, 'MODEL_URI=models:/nom/version\nserving-track=green', fontsize=FONT_MSG,
        color='#2471A3', fontweight='bold', zorder=4)
msg(23, y2 - 0.8, 17, y2 - 0.8, 'Deployment pret', dashed=True)

# Phase 4: Smoke test
phase_box(0.3, -11.0, 25.2, -13.0, '#FDEDEC', '4. Smoke test', '#922B21')
y3 = -11.8
msg(17, y3, 23, y3, 'probePredictionEndpoint()')
msg(23, y3 - 0.6, 17, y3 - 0.6, '200 OK + reponse valide', dashed=True)

# Phase 5: Basculement du traffic
phase_box(0.3, -13.5, 25.2, -15.5, '#F4ECF7', '5. Basculement du traffic', '#7D3C98')
y4 = -14.3
msg(17, y4, 23, y4, 'switchStableServiceTrack(green)')
ax.text(23.5, y4 + 0.4, 'Service selector :\nserving-track=green', fontsize=FONT_MSG,
        color='#7D3C98', fontweight='bold', zorder=4)
msg(23, y4 - 0.6, 17, y4 - 0.6, 'Traffic bascule', dashed=True)
msg(17, y4 - 1.0, 10, y4 - 1.0, 'Statut DEPLOYED', dashed=True)
msg(10, y4 - 1.4, 5, y4 - 1.4, 'Notification succes', dashed=True)
msg(5, y4 - 1.8, 0, y4 - 1.8, 'Resultat affiche', dashed=True)

plt.tight_layout(pad=1)
plt.savefig('images/uml_deployment_sequence.png', dpi=300, bbox_inches='tight',
            facecolor='white', edgecolor='none')
print("OK: images/uml_deployment_sequence.png")
