import flet as ft
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
from adb_shell.auth.keygen import keygen
import os
import socket
from concurrent.futures import ThreadPoolExecutor

# --- 1. Configuración de Llaves RSA de Android ---
adbkey = "adbkey"
if not os.path.exists(adbkey):
    keygen(adbkey)
with open(adbkey, "r") as f:
    priv = f.read()
with open(adbkey + ".pub", "r") as f:
    pub = f.read()
signer = PythonRSASigner(pub, priv)

dispositivo = None
ARCHIVO_IP = "ip_guardada.txt"

def main(page: ft.Page):
    page.title = "Control TV Hyundai"
    page.theme_mode = ft.ThemeMode.DARK
    page.vertical_alignment = ft.MainAxisAlignment.CENTER
    page.horizontal_alignment = ft.CrossAxisAlignment.CENTER
    page.padding = 20
    page.scroll = "auto" # Habilita deslizamiento vertical en el celular

    # --- 2. Variables y Memoria de la App ---
    ip_inicial = ""
    if os.path.exists(ARCHIVO_IP):
        with open(ARCHIVO_IP, "r") as f:
            ip_inicial = f.read().strip()

    # --- 3. Componentes Visuales Superiores ---
    campo_ip = ft.TextField(label="IP de la TV", value=ip_inicial, width=180)
    
    dropdown_ips = ft.Dropdown(
        label="Elige tu TV",
        width=150,
        visible=False,
        options=[]
    )
    
    def seleccionar_ip_dropdown(e):
        campo_ip.value = dropdown_ips.value
        page.update()
        
    dropdown_ips.on_change = seleccionar_ip_dropdown

    texto_estado = ft.Text("Desconectado", color="red", size=16, weight="bold")
    progreso = ft.ProgressRing(width=20, height=20, visible=False)

    # --- 4. Lógica del Escáner Automático ---
    def obtener_mi_subred():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ip_local = s.getsockname()[0]
        except Exception:
            ip_local = "192.168.1.1"
        finally:
            s.close()
        return ".".join(ip_local.split(".")[:3]) + "."

    def verificar_tv(ip, lista_teles):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(0.3)
            if sock.connect_ex((ip, 5555)) == 0:
                lista_teles.append(ip)
            sock.close()
        except:
            pass

    def iniciar_escaneo(e):
        btn_escanear.disabled = True
        progreso.visible = True
        texto_estado.value = "Escaneando red local..."
        texto_estado.color = "orange"
        dropdown_ips.visible = False
        page.update()
        
        subred = obtener_mi_subred()
        teles_encontradas = []
        
        with ThreadPoolExecutor(max_workers=60) as executor:
            ips_a_revisar = [f"{subred}{i}" for i in range(1, 255)]
            executor.map(lambda ip: verificar_tv(ip, teles_encontradas), ips_a_revisar)
            
        btn_escanear.disabled = False
        progreso.visible = False
        
        if len(teles_encontradas) == 0:
            texto_estado.value = "No se encontró TV en puerto 5555."
            texto_estado.color = "red"
        elif len(teles_encontradas) == 1:
            campo_ip.value = teles_encontradas[0]
            texto_estado.value = f"¡TV encontrada en {teles_encontradas[0]}!"
            texto_estado.color = "green"
        else:
            campo_ip.value = teles_encontradas[0]
            dropdown_ips.options = [ft.dropdown.Option(ip) for ip in teles_encontradas]
            dropdown_ips.value = teles_encontradas[0]
            dropdown_ips.visible = True
            texto_estado.value = f"¡Se encontraron {len(teles_encontradas)} dispositivos! Revisa la lista."
            texto_estado.color = "green"
            
        page.update()

    # --- 5. Lógica de Conexión a la TV ---
    def conectar_tv(e):
        global dispositivo
        ip_actual = campo_ip.value.strip()
        with open(ARCHIVO_IP, "w") as f:
            f.write(ip_actual)
        
        try:
            texto_estado.value = f"Conectando a {ip_actual}..."
            texto_estado.color = "orange"
            page.update()
            
            dispositivo = AdbDeviceTcp(ip_actual, 5555)
            dispositivo.connect(rsa_keys=[signer], auth_timeout_s=3)
            
            texto_estado.value = "¡Conectado y listo!"
            texto_estado.color = "green"
        except Exception as error:
            print(f"Fallo de conexión: {error}")
            texto_estado.value = "Error al conectar. Verifica que la TV esté encendida."
            texto_estado.color = "red"
        page.update()

    # --- 6. Lógica de los Botones de Control ---
    def enviar_comando(comando):
        global dispositivo
        if dispositivo:
            try:
                dispositivo.shell(comando)
            except Exception as error:
                print(f"Error ADB: {error}")
                texto_estado.value = "Error al enviar comando."
                texto_estado.color = "red"
                page.update()

    # Comandos de Audio
    def vol_subir(e): enviar_comando("input keyevent 24")
    def vol_bajar(e): enviar_comando("input keyevent 25")
    def vol_mute(e): enviar_comando("input keyevent 164")
    def cambiar_volumen_slider(e):
        volumen_deseado = int(e.control.value)
        enviar_comando(f"service call audio 3 i32 3 i32 {volumen_deseado} i32 1")

    # Comandos ADB de Navegación del Sistema (D-Pad)
    def nav_arriba(e): enviar_comando("input keyevent 19")
    def nav_abajo(e): enviar_comando("input keyevent 20")
    def nav_izquierda(e): enviar_comando("input keyevent 21")
    def nav_derecha(e): enviar_comando("input keyevent 22")
    def nav_ok(e): enviar_comando("input keyevent 23")
    def nav_atras(e): enviar_comando("input keyevent 4") # Botón Back/Atrás

    # --- 7. Construcción de la Interfaz Visual ---
    btn_escanear = ft.ElevatedButton("Buscar TV", on_click=iniciar_escaneo)
    btn_conectar = ft.ElevatedButton("Conectar ADB", on_click=conectar_tv, color="white", bgcolor="#e65100")

    fila_red = ft.Row([campo_ip, dropdown_ips, btn_escanear, progreso], alignment=ft.MainAxisAlignment.CENTER, wrap=True)
    
    slider_volumen = ft.Slider(
        min=0, max=100, divisions=100, value=15, 
        label="{value}", on_change_end=cambiar_volumen_slider,
        width=200, active_color="#2196f3"
    )

    # Panel 1: Audio (Sin botón de energía)
    panel_volumen = ft.Container(
        content=ft.Column(
            [
                ft.Text("CONTROL DE VOLUMEN", size=16, weight="bold", color="white70"),
                ft.Divider(height=5, color="transparent"),
                ft.Row(
                    [
                        ft.ElevatedButton("🔉 -1", on_click=vol_bajar),
                        slider_volumen,
                        ft.ElevatedButton("🔊 +1", on_click=vol_subir),
                    ],
                    alignment=ft.MainAxisAlignment.CENTER, wrap=True
                ),
                ft.ElevatedButton("🔇 Mutear TV", on_click=vol_mute, width=140)
            ],
            alignment=ft.MainAxisAlignment.CENTER, horizontal_alignment=ft.CrossAxisAlignment.CENTER
        ),
        padding=20, border_radius=20, bgcolor="#2b2d31"
    )

    # Panel 2: Cruceta de Navegación del Menú (D-Pad)
    panel_navegacion = ft.Container(
        content=ft.Column(
            [
                ft.Text("NAVEGACIÓN DE MENÚ", size=16, weight="bold", color="white70"),
                ft.Divider(height=10, color="transparent"),
                
                # Fila de Arriba
                ft.Row([ft.ElevatedButton("🔼", on_click=nav_arriba, width=65, height=50)], alignment=ft.MainAxisAlignment.CENTER),
                
                # Fila Central (Izquierda, OK, Derecha)
                ft.Row(
                    [
                        ft.ElevatedButton("◀️", on_click=nav_izquierda, width=65, height=50),
                        ft.ElevatedButton("OK", on_click=nav_ok, width=80, height=50, bgcolor="#2196f3", color="white"),
                        ft.ElevatedButton("▶️", on_click=nav_derecha, width=65, height=50),
                    ],
                    alignment=ft.MainAxisAlignment.CENTER
                ),
                
                # Fila de Abajo
                ft.Row([ft.ElevatedButton("🔽", on_click=nav_abajo, width=65, height=50)], alignment=ft.MainAxisAlignment.CENTER),
                
                ft.Divider(height=10, color="transparent"),
                # Botón de escape/atrás imprescindible para menús
                ft.ElevatedButton("↩️ Volver / Atrás", on_click=nav_atras, width=160, height=40, bgcolor="#455a64", color="white")
            ],
            alignment=ft.MainAxisAlignment.CENTER, horizontal_alignment=ft.CrossAxisAlignment.CENTER
        ),
        padding=20, border_radius=20, bgcolor="#2b2d31"
    )

    # Añadimos la interfaz completa a la pantalla
    page.add(
        ft.Text("📺", size=50),
        ft.Text("Smart Remote", size=28, weight="bold"),
        ft.Divider(height=15, color="transparent"),
        fila_red,
        btn_conectar,
        texto_estado,
        ft.Divider(height=15, color="transparent"),
        panel_volumen,
        ft.Divider(height=15, color="transparent"),
        panel_navegacion
    )

ft.app(target=main)