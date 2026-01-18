import json
import os
from datetime import datetime
import requests  # ← AGGIUNGI QUESTA

# PROVA 1: Legge il file plugins.json scaricato dal workflow
try:
    with open('plugins.json', 'r', encoding='utf-8') as f:
        data = json.load(f)
    print("✅ plugins.json trovato localmente")
    
except FileNotFoundError:
    # PROVA 2: Scaricalo direttamente da GitHub builds
    print("📥 plugins.json non trovato, scarico da GitHub...")
    try:
        url = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/builds/plugins.json"
        response = requests.get(url, timeout=10)
        response.raise_for_status()  # Controlla errori HTTP
        data = response.json()
        
        # Salva per debug
        with open('plugins_downloaded.json', 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)
        print("✅ plugins.json scaricato da GitHub builds")
        
    except Exception as e:
        print(f"❌ ERRORE: Impossibile ottenere plugins.json: {e}")
        # Crea dati vuoti per non bloccare tutto
        data = {"plugins": []}

# Il resto del tuo codice RIMANE UGUALEEE
plugins = data.get('plugins', [])

# Categorizza i plugin per status
status_groups = {
    1: [],  # 🟢 ATTIVI
    3: [],  # 🔵 BETA
    2: [],  # 🟡 LENTI
    0: []   # 🔴 DISATTIVATI
}

for plugin in plugins:
    status = plugin.get('status', 0)
    if status in status_groups:
        status_groups[status].append(plugin)

# Calcola statistiche
total = len(plugins)
attivi = len(status_groups[1])
funzionanti = attivi + len(status_groups[3])  # Attivi + Beta
salute = int((funzionanti / total) * 100) if total > 0 else 0

# Salva dati per telegram_message.py
output = {
    'date': datetime.now().strftime('%d/%m/%Y'),
    'total': total,
    'attivi': attivi,
    'funzionanti': funzionanti,
    'salute': salute,
    'groups': status_groups
}

with open('plugin_data.json', 'w', encoding='utf-8') as f:
    json.dump(output, f, indent=2)

print(f"✅ Plugin analizzati: {total}")
print(f"🟢 Attivi: {attivi} | 🔵 Beta: {len(status_groups[3])}")
print(f"🟡 Lenti: {len(status_groups[2])} | 🔴 Disattivati: {len(status_groups[0])}")
