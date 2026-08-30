#!/bin/zsh
# Watchdog EXTERNO ao processo da CLI (obrigatório antes de testes destrutivos):
# religa <id|uuid> após <segundos>, mesmo que a JVM da CLI tenha morrido (ex.: SIGSEGV
# de binding errado — o failsafe interno morre junto com o processo).
#
# Uso (ANTES de cada teste destrutivo, em outro terminal):
#   ./scripts/watchdog.sh <id|uuid> <segundos> &
# Cancelar quando o teste terminar bem:
#   kill %1   (ou o PID impresso)
#
# Obs.: rode no Terminal local (sessão gráfica) — via SSH as APIs de display não funcionam.
set -u
target="${1:?uso: watchdog.sh <id|uuid> <segundos>}"
secs="${2:?uso: watchdog.sh <id|uuid> <segundos>}"
here="${0:A:h}"
cli="$here/../cli/build/install/macdisplaytoggle/bin/macdisplaytoggle"
if [[ ! -x "$cli" ]]; then
  echo "[watchdog] CLI não encontrada em $cli — rode ./gradlew :cli:installDist antes" >&2
  exit 1
fi
echo "[watchdog] armado (pid $$): 'enable $target' em ${secs}s — cancele com: kill $$"
sleep "$secs"
echo "[watchdog] disparou — religando $target"
exec "$cli" enable "$target"
