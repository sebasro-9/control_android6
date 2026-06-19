import flet as ft
import os
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

ARCHIVO_IP = "ip_guardada.txt"

# --- GENERADOR DE LLAVES DE SEGURIDAD ---
# Si no existen las llaves (ej. recién instalas el APK), las crea.
if not os.path.exists("adbkey"):
    keygen("adbkey")

with open("adbkey", "r") as f:
    priv = f.read()
with open("adbkey.pub", "r") as f:
    pub = f.read()

signer = PythonRSASigner(pub, priv)

# Variable global para mantener la conexión viva y que sea rápido
dispositivo = None

def main(page: ft.Page):
    page.title = "Control Hyundai"
    page.horizontal_alignment = "center"
    page.vertical_alignment = "center"
    page.window_width = 380
    page.window_height = 800 
    page.theme_mode = ft.ThemeMode.DARK 

    if os.path.exists(ARCHIVO_IP):
        with open(ARCHIVO_IP, "r") as f:
            ip_inicial = f.read().strip()
    else:
        ip_inicial = "192.168.1.8"

    campo_ip = ft.TextField(value=ip_inicial, width=200, text_align="center", dense=True)
    texto_estado = ft.Text("", color="green", size=12) 

    def guardar_ip(e):
        global dispositivo
        ip_actual = campo_ip.value.strip()
        with open(ARCHIVO_IP, "w") as f:
            f.write(ip_actual)
        
        try:
            # Añadimos un print para saber que está intentando
            print(f"Intentando conectar a {ip_actual}...") 
            dispositivo = AdbDeviceTcp(ip_actual, 5555)
            dispositivo.connect(rsa_keys=[signer], auth_timeout_s=3)
            texto_estado.value = "¡Conectado y listo!"
            texto_estado.color = "green"
            print("¡Conexión exitosa!")
        except Exception as error:
            # ¡AQUÍ ESTÁ LA MAGIA! Esto imprimirá el error real en tu terminal
            print(f"Fallo de conexión ADB: {error}") 
            texto_estado.value = "Error al conectar."
            texto_estado.color = "red"
            
        page.update()

    boton_guardar = ft.Container(
        content=ft.Text("CONECTAR", weight="bold", size=14, color="white"),
        bgcolor="#FF8C00", padding=10, border_radius=8, on_click=guardar_ip,
        alignment=ft.Alignment(0, 0), width=110
    )

    # --- LÓGICA DE COMUNICACIÓN ADB EN PURO PYTHON ---
    def enviar_comando(codigo):
        global dispositivo
        if dispositivo:
            try:
                dispositivo.shell(f"input keyevent {codigo}")
            except:
                guardar_ip(None) # Intenta reconectar si se cayó la red

    def crear_boton(texto, data_code, color="#1565C0", ancho=120): 
        return ft.Container(
            content=ft.Text(texto, weight="bold", size=20, color="white", text_align="center"),
            bgcolor=color, padding=15, border_radius=10, data=data_code,
            on_click=lambda e: enviar_comando(e.control.data),
            alignment=ft.Alignment(0, 0), width=ancho
        )

    page.add(
        ft.Text("TV Hyundai", size=30, weight="bold"),
        ft.Row([campo_ip, boton_guardar], alignment="center"),
        texto_estado, 
        ft.Divider(),
        ft.Row([crear_boton("▲", 19, color="#424242", ancho=80)], alignment="center"),
        ft.Row([
            crear_boton("◄", 21, color="#424242", ancho=80),
            crear_boton("OK", 66, color="#00695C", ancho=80), 
            crear_boton("►", 22, color="#424242", ancho=80),
        ], alignment="center", spacing=10),
        ft.Row([crear_boton("▼", 20, color="#424242", ancho=80)], alignment="center"),
        ft.Divider(),
        ft.Row([
            crear_boton("VOL +", 24),
            crear_boton("VOL -", 25),
        ], alignment="center"),
        ft.Row([
            crear_boton("CASA", 3),
            crear_boton("ATRÁS", 4),
        ], alignment="center"),
        ft.Divider(),
        crear_boton("APAGAR", 26, color="red") 
    )

ft.run(main)