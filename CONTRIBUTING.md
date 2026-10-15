# Cómo cooperar con Bot Recipe

Gracias por probar mi aporte a la comunidad L2J Mobius. Soy cocinero, no programador profesional: esta receta salió a fuerza de probar en mi propio servidor. Toda ayuda es bienvenida.

## 1. Formas de ayudar (sin saber programar también vale)

1. **Pruébalo y reporta:** abre un Issue con tu versión de Mobius, JDK (`java -version`), y el `RESULT:` de `install.ps1` / `verify.ps1`.
2. **Fotos y videos:** sube tus formaciones, tu Cardinal curando, tu party farmeando. Las pongo en el README.
3. **Traducción:** el README técnico está en español con términos en inglés. Ayuda a traducir al inglés.
4. **Ideas:** To Village [T], scope Command Channel, persistencia entre reinicios. Abre un Issue `idea:` y lo discutimos.
5. **Código:** Fork -> rama `feat/mi-mejora` -> Pull Request con qué probaste.

## 2. Reglas mínimas para un PR

- No subas nunca: `_botai-runs/`, `GameServer.jar`, `backup/`, logs, contraseñas, IPs.
- Si tocas `payload/`, actualiza el SHA-256 en `MANIFEST.json` (hash canónico: bytes con CR eliminados).
- Si tocas el core compilado (`AutoPotion/AutoUse/AutoPlayTaskManager.java`), recalibra `core_entries` (tamaño + major + SHA) compilando con **JDK 27** y los mismos flags del instalador.
- Pasa `verify.ps1 -Runtime "tu\runtime"` y pega el `SUMMARY:` en el PR.
- Un PR = un tema. Pequeño y reversible.

## 3. Cómo probar sin romper tu servidor

```powershell
# 1. Apaga tu servidor (obligatorio)
# 2. Copia tu runtime a una carpeta de prueba si puedes
cd C:\donde-descargaste\bot_recipe
.\install.ps1 -Runtime "C:\mi-servidor-l2j"
.\verify.ps1 -Runtime "C:\mi-servidor-l2j"
# Si algo falla:
.\rollback.ps1 -Runtime "C:\mi-servidor-l2j"
```

## 4. Dónde pedir ayuda

- Issues del repo para bugs e ideas.
- Incluye siempre: versión Bot Recipe (v2.9), `MANIFEST.json recipe/version`, JDK, y captura del error.

## 5. Licencia

Todo aporte se acepta bajo **MIT**, igual que Bot Recipe y L2J Mobius actual. Al abrir un PR aceptas que tu código se distribuya bajo MIT.
