# GafiLoader — Registo de progresso

> Objetivo: manter um registo persistente e verificável do estado do projeto, para retomar o trabalho sem perder decisões, resultados ou próximos passos.
> Última atualização: 2026-10-29
> Repositório: https://github.com/GafiPro/GafiLoader
> Branch principal: `main`

## Objetivo e decisões que não podem ser esquecidas

- O jogo efetivamente executado é Minecraft Java Edition **26.2**.
- O objetivo é permitir mods Fabric feitos para **1.21.11** e mods para 26.2 na mesma instância, quando as dependências e os conflitos o permitirem.
- Os JAR originais dos mods têm de permanecer **byte a byte intactos**. Não exigir conversão/substituição dos originais; qualquer preparação experimental deve gerar ficheiros separados.
- Manter Fabric como base e evoluir a arquitetura existente; não reescrever tudo sem necessidade demonstrada.
- Não declarar compatibilidade completa só porque a build passa ou porque se ultrapassa a verificação de versão.
- A experiência final deve ser simples no Windows: instalador e perfil `gafiloader-26.2`, sem exigir JDK de desenvolvimento ao utilizador final.
- Atualizações não podem duplicar instalações/perfis nem apagar mundos, saves ou mods.

## Estado conhecido em 2026-10-27

### Já existe, segundo o README e o estado de trabalho anterior

- Aplicação Java/Gradle; configuração usa Java 25, ASM, Gson, mapping-io e Tiny Remapper.
- Perfil dedicado `gafiloader-26.2` e bootstrap de pré-arranque.
- O bootstrap lê o diretório real do jogo, deteta mods com metadados Minecraft 1.21.11 e gera/funde `config/fabric_loader_dependencies.json` antes da resolução de mods pelo Fabric.
- O ficheiro de configuração anterior é salvaguardado; os JAR originais dos mods não são alterados por este override.
- Existe scanner de referências a classes/métodos/campos Minecraft e API Fabric, suporte a módulos JAR aninhados, comparação de API/mappings e remapeamento experimental para uma pasta de saída separada.
- README documenta build/testes com `./gradlew clean test` e `./gradlew build`; no Windows usar `gradlew.bat`.
- O workflow GitHub Actions está descrito como produzindo artefactos Windows: instalador x64 com runtime Java e aplicação portátil.

### Bloqueio prioritário conhecido

- O utilizador reportou que Fabric API **0.141.6+1.21.11** continua a ser rejeitada no perfil GafiLoader 26.2.
- O README descreve o override do bloqueio de versão de metadados, mas isso **não** fornece automaticamente classes/métodos removidos, semântica de API, mappings ou compatibilidade de Mixin.
- Estado reportado anteriormente: instalador instalado e perfil `gafiloader-26.2` criado; mappings 1.21.11 descarregados; os mods foram preservados. A instalação de GafiLoader 0.1.1 foi referida, mas o erro real da Fabric API permanece por resolver.
- Não há, neste registo, prova de um arranque completo bem-sucedido com Fabric API 1.21.11 e mods reais. Tratar isso como **não validado**.

## Próximos passos — ordem recomendada

1. **Reproduzir o bloqueio atual** com os ficheiros reais de log, versão exata do loader, JSON do perfil, bootstrap JAR, `fabric_loader_dependencies.json` e metadados dos módulos da Fabric API.
2. Determinar se a rejeição acontece antes do bootstrap, durante a leitura do override, devido a metadados de módulos aninhados, a uma dependência diferente, ou por outro motivo. Não assumir a causa sem evidência.
3. Criar testes de regressão para o caso exato `0.141.6+1.21.11`, incluindo módulos aninhados e configurações de override já existentes; confirmar que a fusão preserva outras regras e não remove dependências indevidas.
4. Fazer build e testes; verificar os logs e confirmar que o bloqueio inicial foi ultrapassado. Registar exatamente o que foi e não foi demonstrado.
5. Continuar a compatibilidade real depois do gate: comparar a API Fabric 1.21.11 com a API para 26.2; inventariar símbolos/classes/métodos em falta; adicionar shims/aliases apenas quando a compatibilidade de descritores e comportamento for validada; testar Mixin, access wideners e dependências.
6. Executar o perfil 26.2 com Fabric API 1.21.11 e pelo menos um mod legado representativo, recolhendo logs completos. Não misturar originais e cópias remapeadas na mesma pasta ativa.
7. Validar instalador em instalação limpa, atualização e reinstalação; confirmar perfil único, atualização de ficheiros correta e preservação de mundos/mods/configurações.
8. Atualizar README, testes e artefactos de release só com funcionalidades verificadas.

## Registo de atividade

### 2026-10-27 — Inicialização deste registo persistente

- Consultado o README da branch principal e a configuração Gradle.
- Confirmada a existência documentada de bootstrap de pré-arranque, override de dependências, scanner, comparador de API/mappings e remapeamento experimental.
- Reafirmado que o override de metadados não equivale a compatibilidade real de runtime.
- Bloqueio mais importante mantido em aberto: rejeição da Fabric API 0.141.6+1.21.11 e ausência de evidência de arranque completo bem-sucedido.
- Criado este ficheiro para centralizar estado, decisões e próximos passos.
- Ainda não foi feita alteração de código nem execução de testes nesta sessão; não afirmar o contrário.

## Protocolo obrigatório para futuras sessões

Em cada sessão de trabalho:
1. Ler este ficheiro, o README e o estado atual do repositório antes de editar.
2. Verificar o histórico recente de commits e os ficheiros alterados para evitar sobrescrever trabalho posterior.
3. Trabalhar na prioridade mais alta ainda aberta e realizar trabalho concreto sempre que possível.
4. Executar testes/builds adequados; separar claramente resultados observados de hipóteses.
5. Atualizar este ficheiro **na mesma sessão** com data, ficheiros/commits alterados, comandos/testes e resultados, falhas conhecidas e próximos passos numerados.
6. Nunca marcar uma tarefa como concluída sem evidência verificável. Se uma ferramenta, acesso ou ambiente impedir o trabalho, registar a limitação e continuar com outra tarefa útil.

## Update 2026-10-27 — GitHub Actions artifact retention

- Updated `.github/workflows/build.yml` so both uploaded Windows artifacts (`GafiLoader-Installer-Windows-x64` and `GafiLoader-Windows-x64`) use `retention-days: 3`.
- Commit: `459ca92cda9d2e024fc25892c64cef6802b723a8`.
- This controls retention for artifacts uploaded by these workflow steps on future runs; it does not immediately delete existing artifacts or change repository-wide defaults.
- Automation cadence request: ChatGPT Automations supports daily/weekly/monthly recurring schedules, not every two hours. `Retomar GafiLoader` remains daily, with a flexible target of 10:00 Europe/Lisbon; the platform does not promise an exact minute.


## Update 2026-10-28 — Preserve the original dependency-override backup

- Reviewed the current LaunchBootstrap, LegacyDependencyOverrideGenerator, LegacyMinecraftVersionDetector, related regression tests, installer, and recent commit history before changing code.
- Confirmed from the official Fabric dependency-override documentation (https://docs.fabricmc.net/players/troubleshooting/dependency-overrides) that version 1, the overrides object, and -depends.minecraft are the documented format. This confirms the intended syntax, but does not prove the user's actual runtime is reading the file.
- Found a concrete backup-safety defect: every launch copied the current generated fabric_loader_dependencies.json over the fixed .bak file, so after the first launch the backup could cease to be the pre-GafiLoader configuration.
- Fixed src/main/java/dev/gafipro/gafiloader/LegacyDependencyOverrideGenerator.java to create .bak only when no backup exists, preserving the first pre-modification configuration across later launches.
- Extended src/test/java/dev/gafipro/gafiloader/LegacyDependencyOverrideGeneratorTest.java to run generation a second time and verify the original backup and unrelated override entries remain intact.
- Commits: daab2e3c0bda434cae3079b5f44fe64862ff6d3b (implementation), c781b56719e4808681e7a024d8d7b9702ef3b6b7 (regression test).
- Validation: GitHub Actions run 40673710829 (https://github.com/GafiPro/GafiLoader/actions/runs/40673710829) was still running when this entry was written. Do not treat the change as CI-validated until that run completes successfully. No local Gradle command was run in this remote-only session.
- P0 remains open: no current latest.log, actual generated config/fabric_loader_dependencies.json, or confirmed failing mod ID from the user's 26.2 instance was available here. The current code has tests for direct legacy metadata, the 0.141.6+1.21.11 artifact-version pattern, and nested modules, but these unit tests cannot prove the real launcher run works.
- Next steps: (1) check the workflow result; (2) obtain the first dependency error plus the bootstrap's [GafiLoader] log lines from the real instance; (3) compare the exact failing mod ID and on-disk override file to determine whether the bootstrap is absent, scanning the wrong game directory, or Fabric is rejecting a different dependency; (4) add a regression test for the observed cause before claiming the gate is fixed.


## Update 2026-10-29 — Fail safely on malformed targeted overrides

- Re-read the current project status, README, Gradle settings/build, bootstrap, dependency override generator, detector, relevant tests, installer, compatibility notes, and recent commits before editing.
- Confirmed the previous run, GitHub Actions run 40673802167, completed successfully on both Ubuntu and Windows; the Windows job also built and uploaded the installer and portable app image.
- Found a robustness defect in `LegacyDependencyOverrideGenerator`: when a targeted legacy mod already had a malformed per-mod override (for example, `"-depends": "not-an-object"`), generation silently replaced that value with an object. That could discard existing configuration instead of failing safely.
- Changed `src/main/java/dev/gafipro/gafiloader/LegacyDependencyOverrideGenerator.java` to reject non-object override entries and non-object `-depends` values for IDs it needs to modify. The exception names the mod ID, and it occurs before the configuration or backup is written.
- Added `refusesToReplaceMalformedExistingOverrideForLegacyMod` in `src/test/java/dev/gafipro/gafiloader/LegacyDependencyOverrideGeneratorTest.java`. It verifies the error is descriptive, the original config text remains unchanged, and no backup is created from a config that was not safely merged.
- Commit: `938dde7adf942019f66dc7e76004b9aa4d716f2c` — [Reject malformed legacy dependency overrides safely](https://github.com/GafiPro/GafiLoader/commit/938dde7adf942019f66dc7e76004b9aa4d716f2c).
- Validation: GitHub Actions run [40855796889](https://github.com/GafiPro/GafiLoader/actions/runs/40855796889) started for this commit. The Ubuntu build/test job completed successfully, and the Windows build/test step completed successfully. At the latest check, the Windows installer packaging step was still in progress, so the entire workflow is not yet recorded as successful. No local Gradle command was run in this remote-only session.
- P0 remains open: there is still no current `latest.log`, actual on-disk `config/fabric_loader_dependencies.json`, or confirmed failing mod ID from the user's Minecraft 26.2 instance. Unit tests do not establish that the real launcher invokes the bootstrap or reads the override.
- Next steps: (1) confirm the full result of run 40855796889; (2) inspect the first real dependency error plus all `[GafiLoader]` log lines; (3) compare the exact failing mod ID with the generated override; (4) add a regression test for the observed runtime cause before claiming the Fabric API gate is fixed.


## Update 2026-10-29 — Confirm Windows installer artifact for user testing

- Re-read the current `PROJECT_STATUS.md`, `README.md`, `build.gradle`, `settings.gradle`, `LaunchBootstrap.java`, `LegacyDependencyOverrideGenerator.java`, `LegacyMinecraftVersionDetector.java`, the relevant tests, `gradle.properties`, and the workflow before deciding whether a code change was justified.
- Checked recent commits: latest code change is `938dde7adf942019f66dc7e76004b9aa4d716f2c`; the subsequent `27215c747c7ac0eb8b37e39a86daf1a5bc5e4111` updates the progress documentation.
- Verified workflow run [40855796889](https://github.com/GafiPro/GafiLoader/actions/runs/40855796889) has completed successfully. Ubuntu job: success. Windows job: success, including tests, app-image packaging, WiX availability, installer creation, and both artifact uploads.
- Confirmed the installer artifact is `GafiLoader-Installer-Windows-x64`, artifact ID `12889645844`, produced from code commit `938dde7adf942019f66dc7e76004b9aa4d716f2c`; the workflow's artifact digest is `sha256:c8585e0f55043e051480f92daa6caacafc6441a8e7f08879a3a847c2ed168119`. The artifact expires at 2026-11-01 10:14:42 UTC (three-day retention).
- Downloaded and extracted the artifact in this session. The contained installer is `GafiLoader-0.1.601.exe`, 51,020,800 bytes; local SHA-256: `7ee24a9262a49c67b8d823df9726313c54396b05dedf66bdbaf5b4be123a0740`. The containing artifact ZIP SHA-256 matches the workflow digest above.
- No local Gradle command was run here. CI results are the evidence for compilation/tests and Windows packaging; no real Minecraft runtime test was performed.
- No speculative source-code patch was made in this round: the actual user-side rejection is still missing its `latest.log`, `[GafiLoader]` bootstrap output, and generated `config/fabric_loader_dependencies.json`. The static review confirms the bootstrap attempts to write the override before reflectively calling Fabric's `KnotClient`, but cannot prove the user's launcher invokes this profile or that the reported rejection is the Minecraft gate.
- P0 remains open. Next: user installs this build and launches the dedicated `gafiloader-26.2` profile once; if it still fails, collect the first relevant dependency error, all `[GafiLoader]` lines, and the generated override file (redact usernames/private paths if desired). Compare the failing mod ID to the generated override before changing code.


## Update 2026-10-29 — Launch preflight safety, diagnostics, and regression fixtures

- Reviewed the current main history and the real Main, ProfileBootstrapInstaller, LaunchBootstrap, dependency generator, and tests before editing. Work was prepared on branch fix/launch-preflight-diagnostics; it has not yet been validated by CI at the time of this entry.
- Found that Main.runDefaultSetup() called the dependency generator during setup and could permanently write overrides to the shared .minecraft/config/fabric_loader_dependencies.json before the dedicated profile was launched. Removed that setup-time write. The setup path now installs the bootstrap only, reports whether profile installation succeeded, and directs the user to launch gafiloader-26.2.
- Added explicit bootstrap logs at entry, including the actual --gameDir, exact override config path, whether legacy IDs were found, and the point where control is passed to Fabric Loader. This should distinguish “bootstrap never ran/wrong game directory” from a later dependency-resolution error.
- Made launch-generated dependency overrides temporary. The bootstrap captures the existing config bytes (if any), generates overrides before calling Fabric, and restores the prior bytes on normal return/error or JVM shutdown, but only if the file still matches the generated bytes. If another process changes the file during the session, GafiLoader preserves that external change and logs a warning. A hard process kill or power loss can still bypass shutdown cleanup; this limitation is documented in README.
- Added regression tests in src/test/java/dev/gafipro/gafiloader/LaunchBootstrapTest.java for exact-byte restoration, deletion of a config created solely for the launch, and preserving an external modification.
- Added handlesFabricApiAggregatorAndNestedLegacyModulesTogether to src/test/java/dev/gafipro/gafiloader/LegacyDependencyOverrideGeneratorTest.java. It models fabric-api version 0.141.6+1.21.11 plus nested fabric-api-base and fabric-rendering-v1 modules, checks all three receive the Minecraft-gate removal, and verifies pre-existing Java and unrelated-mod rules survive.
- Updated README to document setup's no-write behavior, temporary override lifecycle, external-edit protection, and the abrupt-termination limitation.
- Files changed on the branch: src/main/java/dev/gafipro/gafiloader/Main.java, src/main/java/dev/gafipro/gafiloader/LaunchBootstrap.java, src/test/java/dev/gafipro/gafiloader/LaunchBootstrapTest.java, src/test/java/dev/gafipro/gafiloader/LegacyDependencyOverrideGeneratorTest.java, and README.md.
- Validation: no local Gradle execution was possible in this remote-only environment. Unit tests/build and Windows installer packaging are pending CI until this branch is integrated into main. Do not report them as passed yet.
- Important limit: these changes improve the real launch preflight and config safety, but they do not automatically remap legacy mod bytecode or recreate removed Minecraft/Fabric APIs. Opening the menu with actual 1.21.11 mods still requires runtime verification; no successful end-to-end launch is established yet.
- Next steps: (1) integrate this branch and verify Windows/Linux CI; (2) download and hash the newly built Windows installer; (3) test the profile with the user's real latest.log and config; (4) if the game still fails, use the new [GafiLoader] lines to determine whether the failure is the Minecraft gate or a later bytecode/API/Mixin incompatibility.


## Update 2026-10-29 — CI concluída e instalador 0.1.604 produzido

- A branch de correções foi integrada em main no commit `4547a867dc33d8759acd0e650bffd2452333094b`; a execução CI associada [40896292875](https://github.com/GafiPro/GafiLoader/actions/runs/40896292875) terminou com conclusão `success`.
- Resultado verificado nos jobs da execução 604: Ubuntu — sucesso (`clean test build`); Windows — sucesso (`clean test installDist`, app-image com runtime Java incluído, verificação WiX, criação do instalador e upload dos artefactos).
- Alterações de código/testes da ronda: `Main.runDefaultSetup()` deixou de gerar permanentemente overrides na configuração partilhada durante a instalação; `LaunchBootstrap` agora regista entrada/caminhos reais e prepara o override antes do Fabric, tentando restaurar a configuração anterior no fim da sessão ou no shutdown do JVM, desde que o ficheiro ainda corresponda aos bytes gerados; se detectar alterações externas, preserva-as. Existe uma limitação documentada para encerramentos forçados/perda de energia.
- Testes novos nesta ronda: restauração byte a byte da configuração original, remoção de configuração criada só para a sessão, proteção contra alterações externas e fixture combinada para `fabric-api 0.141.6+1.21.11` com módulos aninhados `fabric-api-base` e `fabric-rendering-v1`, preservando regras de Java/outros mods.
- README atualizado para explicar a configuração temporária e o comportamento de recuperação.
- Artefacto Windows: `GafiLoader-Installer-Windows-x64`, ID `12907740534`, execução `40896292875`; expira em 2026-11-01 14:56:42 UTC. SHA-256 do ZIP de artefacto (confirmado contra o digest publicado pela Actions): `048353554582befd48a9d5aa5a45c6d294f0e3e874820ea5290e5c0c6dddd0dc`.
- EXE extraído: `GafiLoader-0.1.604.exe`, 51,024,896 bytes, SHA-256 `d304ef55e369327cb5d736a451edadb6d0e073739024f2e8857fd51c322df380`.
- Comandos Gradle locais não executados: a validação ocorreu na CI do GitHub Actions. Ainda não foi executado um arranque end-to-end de Minecraft 26.2 com a instalação real do utilizador.
- P0 continua aberto até existir evidência do arranque real. Este conjunto de alterações melhora o diagnóstico e o isolamento temporal do override, mas não remapeia automaticamente todos os mods antigos nem recria APIs removidas; portanto, não se deve prometer que qualquer conjunto de mods 1.21.11 abre sem erros.
- Próximo passo: o utilizador testa `GafiLoader-0.1.604.exe` com o perfil `gafiloader-26.2`. Se falhar, obter as linhas `[GafiLoader]` e o primeiro erro de dependência do `logs/latest.log`, comparando-os com o caminho de config agora impresso.


## Update 2026-10-29 — Create a visible Minecraft Launcher installation

- User reported that installer 0.1.604 did not add any installation to Minecraft Launcher. Static review confirmed the cause: ProfileBootstrapInstaller returned early unless `.minecraft/versions/gafiloader-26.2/gafiloader-26.2.json` already existed, and it never inserted a `gafiloader-26.2` entry into the launcher’s `profiles` map. Installing the GafiLoader desktop app therefore did not create a visible launcher installation.
- Changed `ProfileBootstrapInstaller` to discover an installed Fabric version JSON targeting Minecraft 26.2, copy it to the dedicated `versions/gafiloader-26.2/` path, set the dedicated ID and bootstrap entrypoint, and install the bootstrap JAR. The original Fabric source profile and vanilla 26.2 JSON are left byte-for-byte unchanged.
- Added registration of a `GafiLoader 26.2` custom installation (`lastVersionId: gafiloader-26.2`) into every existing recognized launcher profiles file: `launcher_profiles.json` and/or `launcher_profiles_microsoft_store.json`. Existing launcher settings, accounts, unrelated entries, and custom fields are preserved; writes use temporary files and atomic move. The app now tells the user to close Minecraft Launcher before setup and reopen it afterward to refresh the Installations list.
- If no launcher profile file exists, setup reports that the official Launcher must be opened once and closed. If no Fabric 26.2 source profile exists, setup stops before writing the version JSON or bootstrap library and asks the user to install Fabric 26.2 once; this intentionally avoids creating a launcher entry that cannot start Fabric.
- Added tests for creating a new dedicated version and launcher entry, preserving the source Fabric and vanilla files, preserving unrelated launcher profile fields, supporting `launcher_profiles_microsoft_store.json`, and refusing to leave an orphan when no Fabric 26.2 source exists. Updated the pre-existing unexpected-entrypoint test setup to include a launcher profile file so it tests the intended validation.
- Updated README to explain the newly created launcher installation, closing/reopening the launcher, and the explicit prerequisite if no Fabric 26.2 install exists.
- Files changed: `src/main/java/dev/gafipro/gafiloader/ProfileBootstrapInstaller.java`, `src/main/java/dev/gafipro/gafiloader/Main.java`, `src/test/java/dev/gafipro/gafiloader/ProfileBootstrapInstallerTest.java`, `README.md`.
- Status at time of entry: changes are on branch `fix/create-launcher-installation`; CI has not yet run for these changes. No local Gradle command has been run in this remote-only environment. Do not provide a new installer until Windows and Linux test/build results are successful and the new Windows artifact is confirmed.
- Next steps: inspect the branch diff; fast-forward the changes to `main`; verify GitHub Actions; download and hash the new installer; report clearly if no Fabric 26.2 source profile is found on the user's machine.


## Update 2026-10-29 — Launcher installation fix validated and merged

- Pull request [#14](https://github.com/GafiPro/GafiLoader/pull/14) was merged into `main` as commit `4cbfb956de76c79bb0dfdf614bb80b65067429c5`.
- CI for the exact merged tree passed in [workflow run 40920154939](https://github.com/GafiPro/GafiLoader/actions/runs/40920154939): Ubuntu `clean test build` — success; Windows `clean test installDist` — success; Windows app-image packaging, WiX setup, installer packaging and artifact uploads — success.
- This addresses the user's report that no Minecraft Launcher installation appeared. The old implementation required the dedicated version JSON to already exist and did not register a launcher profile. The corrected implementation creates the dedicated version JSON from an installed Fabric 26.2 profile and adds the `GafiLoader 26.2` entry to the existing launcher profile file(s).
- Installer artifact from that successful CI run: ID `12917168288`, name `GafiLoader-Installer-Windows-x64`, run build version `0.1.606`, ZIP SHA-256 `5fd206ee489c3e48c935b1d9355fc7d24e4dbe096ee9164897d429ac8772271b` (matches the downloaded ZIP). Extracted executable: `GafiLoader-0.1.606.exe`, 51,033,088 bytes, SHA-256 `9de8467b17a735eaed97b1f9db3f161a2f795fc29fd85a6184fe45191da35792`.
- No local Gradle commands were run; validation was performed by GitHub Actions. Unit tests confirm version profile creation, launcher profile registration, preservation of source Fabric and vanilla JSON, preservation of unrelated launcher settings, support for the Microsoft Store launcher profile file, and safe refusal when the launcher profile file or Fabric 26.2 source is missing.
- Runtime limitation remains: the installation flow has not been exercised against the user's actual Minecraft Launcher on Windows. The bootstrap still only removes the declared Minecraft-version gate; it does not guarantee that every 1.21.11 mod or Fabric API module is binary/behaviorally compatible with 26.2.
- User test instructions: fully close Minecraft Launcher, run the new installer, then reopen the Launcher. The installer expects an already installed Fabric profile for Minecraft 26.2 to use as its source; if none exists, it reports that requirement without leaving an orphan profile or changing vanilla/mod files.
- Next step: user verifies that `GafiLoader 26.2` appears in the Installations tab. If it does not, report the setup console message and whether `launcher_profiles.json` or `launcher_profiles_microsoft_store.json` exists. If it appears but the game fails, collect `[GafiLoader]` log lines and the first dependency error from `logs/latest.log`.
