#!/usr/bin/env bash
# Командный самотест: дев-сервер с FTB Teams + клиенты Alice и Bob.
# Итоги — строки SELFTEST team в server.log и alice.log; снимок карты Alice — run_alice/screenshots.
cd "$(dirname "$0")/.." || exit 1
G="./gradlew --offline -Dnet.minecraftforge.gradle.check.certs=false -Pwith_ftb -Ppergament_teamtest --console=plain"
rm -rf run_server/world run_alice/pergament run_bob/pergament run_alice/screenshots
mkdir -p run_server run_alice run_bob
[ -f run_server/eula.txt ] || echo "eula=true" > run_server/eula.txt
[ -f run_server/server.properties ] || printf 'online-mode=false\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nlevel-seed=pergament\n' > run_server/server.properties
for d in run_alice run_bob; do
  [ -f $d/options.txt ] || printf 'renderDistance:4\npauseOnLostFocus:false\nonboardAccessibility:false\nskipMultiplayerWarning:true\n' > $d/options.txt
done
: > server.log; : > alice.log; : > bob.log
$G runServer > server.log 2>&1 &
until grep -a -q 'Done (' server.log; do sleep 2; done
$G runClient > alice.log 2>&1 &
until grep -a -q 'Alice joined' server.log; do sleep 2; done
$G runClient -Ppergament_bob > bob.log 2>&1 &
wait
echo "teamtest finished"
