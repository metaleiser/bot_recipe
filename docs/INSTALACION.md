# Instalación paso a paso (novatos)

> Solo necesitas PowerShell y tu servidor apagado. No tocas código.

## 0. Qué necesitas

- Windows con PowerShell (tecla Windows, escribe `PowerShell`, Enter).
- Tu servidor L2J Mobius CT 2.6 High Five actual (el que ya corre con JDK 27).
- JDK 27 instalado (BellSoft Liberica 27+36). Comprueba:
```powershell
java -version
```
Debe decir `27`. Si dice 17/21, instala el 27 y ajusta `JAVA_HOME`.
- Servidor **apagado**. El instalador nunca lo apaga solo.

## 1. Descarga

```powershell
git clone https://github.com/metaleiser/bot_recipe.git
cd bot_recipe
```

Sin git: descarga el ZIP del repo y descomprímelo, entra a la carpeta.

## 2. Instala (cambias 1 cosa)

Cambia SOLO `"C:\mi-servidor-l2j"` por tu carpeta del servidor (la que tiene `game` y `libs`):

```powershell
.\install.ps1 -Runtime "C:\mi-servidor-l2j"
```

Espera `RESULT: PASS`. Tarda poco, como ya viste: valida JDK 27, respalda 10 destinos, copia 10 datapack, compila 3 sources del core contra tu JAR, parchea 5 entries, aplica 13 claves ini, y se autoverifica.

Backups en: `C:\mi-servidor-l2j\_botai-runs\<fecha>\`. No borrar.

## 3. Verifica

```powershell
.\verify.ps1 -Runtime "C:\mi-servidor-l2j"
```

Busca `SUMMARY: PASS=... FAIL=0`. Si hay `SKIP`, lee la nota (normalmente falta pre-estado).

## 4. Prueba en juego

1. Enciende login/game.
2. Entra con tu main (ej. Cardinal 85), invita a tu party a tus alters.
3. Escribe `.bot`, pulsa `ALL`, luego `Formation ON`, `Assist ON`.
4. Sal a campo (no ciudad: en PEACE los buffs no castean por diseño del core).
5. `.bot status` para ver slots 2..9.

## 5. Si algo falla

El instalador dice `PROBLEMA / CAUSA / ACCION`. Si dejó backup:

```powershell
.\rollback.ps1 -Runtime "C:\mi-servidor-l2j"
```

Si dice `ALREADY-INSTALLED`, ya está bien. Usa `-Reinstall` solo si quieres forzar.

## 6. El nombre BOTAI no te debe asustar

Verás `BOTAI-RECIPE` en `MANIFEST.json` y `_botai-runs` en tu servidor. Se dejan quietos a propósito para no invalidar tu instalación. Tú solo usas `.bot` en juego y la carpeta `bot_recipe` descargada.
