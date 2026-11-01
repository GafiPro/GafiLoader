# GafiLoader — Registo de progresso

> Objetivo: manter um registo persistente e verificável do estado do projeto, para retomar o trabalho sem perder decisões, resultados ou próximos passos.
> Última atualização: 2026-10-31
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

## Estado atual — 2026-11-01

- A cabeça atual de `main` é `583ef113d93f35bb0a662dddb07b6350f4175c1b`, merge commit da PR #27.
- **Erro de preflight mais recente fornecido pelo utilizador:** em 2026-10-31 às 13:10:08 UTC, `ClassTweaker line 9: No 1.21.11 Intermediary mapping exists for ClassTweaker method net/minecraft/class_1959$class_5482#<init>(ZFLnet/minecraft/class_1959$class_5484;F)V`.
- **Causa confirmada no código:** `LegacyClassTweakerMappings.mapMember` exigia uma entrada Tiny para todos os métodos. O nome JVM `<init>` é invariável e pode não aparecer nas mappings Intermediary; o remapper falhava antes de remapear o proprietário e o descritor.
- **Correção integrada em `main`:** PR [#26](https://github.com/GafiPro/GafiLoader/pull/26), merge commit `4c56e694471698415d4a07d9ba0a7cb8b501ea89`. Para `<init>` e `<clinit>`, o código preserva o nome JVM, remapeia o proprietário para `official` e remapeia os tipos do descritor. Não inventa um nome de método.
- **Regressão específica:** `LegacyClassTweakerRemapperTest.remapsConstructorOwnerAndDescriptorWhenIntermediaryMappingsOmitInit` usa o proprietário e o descritor do erro real e verifica os nomes Mojang das duas classes aninhadas.
- **Correção adicional integrada em `main`:** PR [#27](https://github.com/GafiPro/GafiLoader/pull/27), merged em 2026-11-01 às 10:44:56 UTC, merge commit `583ef113d93f35bb0a662dddb07b6350f4175c1b`. `LegacyMinecraftVersionDetector` reconhece wildcards Fabric por componente (`26.x`, `26.2.x`, `26.2.*` e `X` maiúsculo) ao decidir se uma alternativa de dependência admite 26.2. Isto evita remover a dependência Minecraft quando um array OR já contém uma alternativa compatível com 26.2.
- **Testes dos wildcards:** `LegacyMinecraftVersionDetectorTest.evaluatesFabricWildcardAlternativesAgainstMinecraft262` cobre `26.2.x`, `26.2.X`, `26.x`, `26.2.*` e `26.1.x` (que não deve admitir 26.2). A decisão segue a especificação oficial de `fabric.mod.json`: arrays representam alternativas OR e wildcards `.x` / `.*` são suportados.
- **Validação da PR #27:** GitHub Actions run [41298700072](https://github.com/GafiPro/GafiLoader/actions/runs/41298700072) terminou com sucesso em Ubuntu e Windows. Ambos executaram os testes/build; o Windows também criou a app image e o instalador.
- **Validação da `main` após merge:** GitHub Actions run [41298829441](https://github.com/GafiPro/GafiLoader/actions/runs/41298829441) terminou com sucesso. Ubuntu `clean test build` — sucesso; Windows `clean test installDist` — sucesso. No Windows passaram também a criação da app image, a criação do instalador e os uploads.
- **Instalador correspondente à `main` atual:** artefacto Actions `13080158094`, `GafiLoader-0.1.749.exe`, 51 475 456 bytes. O ZIP descarregado corresponde ao digest publicado `sha256:319be4591e977c467fdb9185aab75b82a451ed8010ae7be54d7f28bd4c4ff0f0`; SHA-256 do EXE extraído: `d7d1f6f25da573c1ec0c06676f1debcd0fc39b9e4ff5636fe61a0fc3b7f096ca`. O artefacto expira em 2026-11-04 às 10:46:23 UTC.
- **Runtime continua por validar:** não existe ainda um novo `gafiloader-preflight.log` / `latest.log` produzido pelo EXE 0.1.749 no computador do utilizador. A CI demonstra compilação, testes e empacotamento; não demonstra que Minecraft 26.2 inicia ou que a Fabric API 0.141.6+1.21.11 funciona em jogo real.

## Próximos passos — ordem recomendada

1. Testar `GafiLoader-0.1.749.exe` no perfil dedicado `gafiloader-26.2`; confirmar no `logs/gafiloader-preflight.log` que o ClassTweaker da Fabric API passa a namespace `official` e que o preflight continua para além da linha 9.
2. Se o arranque ainda falhar, recolher o novo `logs/gafiloader-preflight.log` e o primeiro erro relevante de `logs/latest.log`; diagnosticar a próxima falha sem misturar erros de ClassTweaker, linkage, Mixin e API.
3. Confirmar que Fabric carrega as cópias staged em `.gafiloader/compat-mods` quando há transformação e que os JAR originais permanecem byte a byte intactos.
4. Testar a Fabric API 0.141.6+1.21.11 e pelo menos um mod legado representativo até ao menu/jogo; manter compatibilidade funcional geral como não validada até haver essa evidência.

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


## Update 2026-10-29 — Audit of repeated install/launch failure

- Audited the current main source and latest Windows/Linux CI. Main was at 9c44c078859e800d1b93e67cbab2baca0ee5cc53; workflow run https://github.com/GafiPro/GafiLoader/actions/runs/40920666695 passed both OS jobs and built/uploaded a Windows installer. This proves compilation, unit tests and packaging only; it does not launch the real Minecraft Launcher or prove the profile appears in its UI.
- Confirmed current setup has a hard prerequisite: an installed Fabric profile whose version JSON declares Minecraft 26.2, Fabric's KnotClient entrypoint, and a net.fabricmc:fabric-loader library. If none exists, setup intentionally exits without creating the GafiLoader profile. That is one plausible cause of a missing installation, but the user's local console output has not been inspected, so it is not confirmed as their cause.
- Confirmed setup errors were only printed in the console and were not retained as a log. Confirmed the bootstrap trusted the embedded JAR after checking only that the resource existed, and the runtime had no persistent record of the exact mods/config paths or of a generated override validation.
- Work on branch audit/installer-diagnostics: added .minecraft/logs/gafiloader-setup.log to preserve the setup result, chosen paths, application version and exception details; validate the embedded bootstrap JAR manifest/version/required entries before writing the dedicated version/profile; add logs/gafiloader-preflight.log inside the active game directory; honor Fabric's fabric.modsFolder system property; validate the generated dependency override JSON and report path/scan/failure details.
- Added regression tests for rejecting a corrupt bootstrap archive, testing custom mods-folder resolution, and validating generated overrides. CI is pending; do not report these edits as passing until the branch workflow finishes.
- No end-to-end Minecraft test is available in this remote session and no user-side logs have been received. Therefore the actual cause remains unconfirmed. Next: check this branch's CI result; if it passes, integrate and provide the new Windows build. The next user run should produce %APPDATA%\\.minecraft\\logs\\gafiloader-setup.log; if the profile is visible but fails to launch, also inspect the actual instance's logs\\gafiloader-preflight.log and latest.log. Redact the Windows username/private paths before sharing.


## Update 2026-10-29 — Audit completada; diagnósticos integrados em main

- A auditoria foi integrada através do PR [#15](https://github.com/GafiPro/GafiLoader/pull/15), squash commit `163bb0e90de0b13cefa30d3be8639a0875ecd7ca`.
- O workflow de PR [#615](https://github.com/GafiPro/GafiLoader/actions/runs/40928202173), executado para a árvore final de código `aad79897cd2088c1a50a7686d5841f596a722130`, terminou com sucesso em Linux e Windows. O job Windows passou testes/build, empacotamento de aplicação portátil e criação/upload do instalador. Isto prova compilação, testes e embalagem; não é um teste da UI do Minecraft Launcher nem do arranque do jogo real.
- O instalador produzido por esse workflow tem artefacto `GafiLoader-Installer-Windows-x64`, ID `12920338915`, digest do ZIP `sha256:d80d9819d5060cb414b10de8539bc03439cdb4e28627634fa6a92d37cc2eaf44`, retenção até 2026-11-01 18:11 UTC. O artefacto portable é `GafiLoader-Windows-x64`, ID `12922245105`.
- Auditoria e correções:
  - A instalação agora grava o resultado, a versão da aplicação, os caminhos e a stack trace da falha em `%APPDATA%\\.minecraft\\logs\\gafiloader-setup.log`.
  - Antes de alterar o perfil, verifica se o bootstrap embutido é um JAR válido, se o manifesto declara a classe de entrada certa, se a versão coincide e se existem as classes essenciais.
  - Quando cria ou repara a entrada GafiLoader, preenche apenas definições em falta a partir do perfil Fabric de origem (p. ex. `gameDir`, argumentos Java, Java selecionado e resolução), sem substituir opções personalizadas já presentes no perfil GafiLoader.
  - O bootstrap cria `logs/gafiloader-preflight.log` no diretório de jogo real, regista o diretório `mods`, o caminho da configuração, Java, mods detetados, overrides e falhas; também respeita `fabric.modsFolder`.
  - A configuração de dependency override gerada é validada antes da chamada ao Fabric. Configuração anterior malformada é preservada como `.corrupt`, tenta recuperar-se um `.bak` válido e nunca se substitui um backup existente por conteúdo inválido.
  - Os testes de regressão cobrem JAR bootstrap inválido, validação da configuração, diretório de mods personalizado, recuperação de configuração corrompida e reparação de opções de perfil omitidas.
- CI do PR: sucesso. CI da branch principal após merge: execução `40928463864` (#616) estava em fila no momento deste registo.
- **Causa exata no computador do utilizador: ainda não confirmada.** Não recebemos o log de setup nem dados reais do Launcher. A hipótese de maior prioridade para a instalação não aparecer continua a ser uma pré-condição que não se cumpre (não existe perfil Fabric válido de Minecraft 26.2, o Launcher guarda o ficheiro de perfis noutra raiz, ou o Launcher estava aberto e voltou a gravar o JSON). A nova versão permite distinguir essas possibilidades sem adivinhar.
- Próximo passo obrigatório: usar o instalador desta auditoria e, se o perfil continuar ausente, partilhar o conteúdo de `%APPDATA%\\.minecraft\\logs\\gafiloader-setup.log`. Se o perfil aparecer mas falhar ao arrancar, partilhar também `logs/gafiloader-preflight.log` e o primeiro erro relevante de `logs/latest.log`. Redigir nomes de utilizador/caminhos privados antes de partilhar.



## Update 2026-10-29 — Segunda auditoria do registo no Launcher e limpeza segura

- Aberta a PR [#16](https://github.com/GafiPro/GafiLoader/pull/16), branch `audit/launcher-registration-hardening`, a partir da `main` em `cc389580fae3e6fb67caeb76826532af7e20024b`.
- Nova classe `MinecraftDirectoryResolver`: respeita o override explícito `GAFILOADER_MINECRAFT_DIR` / `-Dgafiloader.minecraftDir`, prefere `%APPDATA%\\.minecraft` quando válido, tenta raízes alternativas conhecidas quando não há perfil válido no caminho padrão e regista a origem da escolha. `Main` e o assistente interativo usam o mesmo resolvedor.
- `ProfileBootstrapInstaller` agora exige `inheritsFrom: 26.2`, recusa perfis Fabric com herança contraditória, valida os bytes reais dos ficheiros `.class` do bootstrap (incluindo leitura completa/CRC) e compara os bytes atuais dos ficheiros de perfis do Launcher com os originais antes de escrever, para abortar se detectar alterações concorrentes.
- Após uma instalação e registo bem-sucedidos, procura referências de bibliotecas GafiLoader em todos os JSON de versões legíveis e backups `.gafiloader-backup`. Apaga apenas pastas de versão não referenciadas no caminho dedicado `libraries/dev/gafipro/gafiloader-bootstrap/`, com ficheiros esperados e sem seguir symlinks. Se encontrar JSON ilegível, conteúdo inesperado ou falha de I/O, prefere não apagar. Não mexe em mods, saves, outros artefactos/bibliotecas, perfis Fabric/vanilla ou na instalação Windows atualmente em execução.
- Os testes deixaram de criar entradas `.class` de zero bytes e passam a incorporar bytes de classes compiladas reais. Acrescentados testes para resolução da raiz Minecraft, rejeição de bytes de classe inválidos e limpeza de artefactos antigos não referenciados, preservando os ainda necessários.
- README documenta a descoberta da raiz, a variável de ambiente explícita, verificações e escopo restrito da limpeza. O instalador Windows usa o mesmo upgrade UUID estável já configurado no workflow; portanto, uma nova build deve atualizar a instalação do aplicativo por cima da anterior, sem apagar manualmente a pasta do programa.
- Commits de código/testes/documentação na branch até ao início desta atualização: `c217dbe1fdca7d906ed4725e36a96a06f88a5fad`, `34d084fb9e7b3396c588ced3a1619a168bb2ce44`, `893d06039db7e8e91bbb6f1fd8a21af62deb5b56`, `1599daa3f981fce707e67c39ea82aeceaf6b6006`, `574a88f7546c1f42e611736fb06482b0e1c137e0`, `a2349cf3e0ee25f514e845c2124d79d58afc4622`, `d134846af1db4f6f4162f7a6f6b60cd3d44e248a`, `77d9d5663755235b72a8ad708f6beb535e0b44cb`, `36c8729650ad0aec00ccd85b8a903ddb386b9b31`.
- Validação observada para o commit `36c8729650ad0aec00ccd85b8a903ddb386b9b31`: GitHub Actions run [40949042024](https://github.com/GafiPro/GafiLoader/actions/runs/40949042024). Linux `clean test build` — sucesso; Windows `clean test installDist` — sucesso. Empacotamento da app image/instalador estava ainda em execução quando este registo foi acrescentado; confirmar o resultado final no workflow antes de considerar esta ronda validada.
- Limite: mesmo com CI verde, não há teste end-to-end com o Minecraft Launcher real do utilizador. Ainda não confirmar que o perfil aparece na UI nem que os mods de 1.21.11 arrancam sem incompatibilidades. Após a CI final, obter o novo artefacto Windows e testar na instalação real. Se o perfil continuar ausente, ler `logs/gafiloader-setup.log`; se aparecer mas falhar, ler `logs/gafiloader-preflight.log` e o primeiro erro relevante de `logs/latest.log`.

## Update 2026-10-30 — Confirmar correção do linkage de EntityType.PLAYER

- Estado inicial: reaberto o projeto a partir do repositório real, lendo este registo, README, `build.gradle`, `settings.gradle`, workflow, bootstrap, gerador de overrides, detector de versão e testes do transformador. Verificados os commits recentes; não havia atualização de status depois da integração da PR #19.
- Evidência do crash: `NoSuchFieldError: EntityType.PLAYER` durante o primeiro reload de recursos em `EntityRenderers`, via `fabric-rendering-v1`. O stack trace demonstra que este arranque passou a fase de resolução de mods; o erro atual é de linkage binário, não a mensagem inicial de incompatibilidade de versão.
- Verificação externa do símbolo: a documentação Yarn oficial de 1.21.11+build.6 confirma `field_6097` como o campo `PLAYER` de `class_1299`. Isso sustenta a regra específica, em vez de inferir o nome a partir do teste.
- Código integrado em `main`: commit `6d68dc35cd9b62f076974ce4514854419544972a`; PR [#19](https://github.com/GafiPro/GafiLoader/pull/19). O transformador trata o símbolo nomeado e o símbolo Intermediary publicado, incluindo referências em JARs aninhados. O teste de regressão verifica a transformação e preservação byte a byte dos JARs originais.
- Validação real observada: workflow [40963584988](https://github.com/GafiPro/GafiLoader/actions/runs/40963584988) concluído com sucesso nos jobs Ubuntu e Windows. O Windows também empacotou a aplicação com runtime e o instalador. Não se executou `./gradlew` nesta sessão remota e não se executou Minecraft real.
- Tentativa adicional de teste local: `git clone --depth 1 https://github.com/GafiPro/GafiLoader.git /tmp/GafiLoader-verify` falhou porque o ambiente não conseguiu resolver `github.com`; o JDK local é Java 21, enquanto o projeto exige Java 25. Não se executaram testes Gradle locais. A validação de build/teste citada acima vem exclusivamente da CI.
- Artefacto verificado localmente: `GafiLoader-0.1.631.exe`, 51,442,688 bytes, SHA-256 `7164fe1cbe545fa66823ef28129dd526d81bfb2dccf1f0a996a36eabfb001577`. O ZIP da Actions corresponde ao digest publicado `34eae6910418022be36a4e65a6aa378192a1b664dbfdbc9b5318eb53780faec8`; expira em 2026-11-01 22:10:08 UTC.
- Limitação principal: não existe ainda um novo `latest.log` nem `gafiloader-preflight.log` gerado pelo EXE 0.1.631 no computador do utilizador. Portanto, não afirmar que o crash está resolvido em runtime.
- Próximos passos prioritários: (1) testar o EXE 0.1.631 no perfil `gafiloader-26.2`; (2) confirmar no `logs/gafiloader-preflight.log` que o transformador reescreveu a referência e que Fabric usa `.gafiloader/compat-mods`; (3) se houver novo crash, analisar o primeiro erro relevante do `logs/latest.log` e o preflight completo antes de introduzir outra regra; (4) manter a compatibilidade geral de Fabric API 1.21.11 marcada como experimental até haver arranque real e testes de uso.


## Update 2026-10-31 — Deteção de ClassTweaker com BOM integrada

- Reaberto o repositório antes de editar: lidos `PROJECT_STATUS.md`, `README.md`, `build.gradle`, `settings.gradle`, `LaunchBootstrap.java`, `LegacyDependencyOverrideGenerator.java`, `LegacyMinecraftVersionDetector.java`, `LegacyModCompatibilityTransformer.java`, `LegacyClassTweakerRemapper.java` e os testes de bootstrap/overrides/ClassTweaker.
- Verificados os commits recentes e a branch principal. O commit mais recente é `8d59966664216b659580d233bce0138fd084f0e1`; `compare_commits(main, 8d59966)` devolveu `identical`, confirmando que a correção está em `main`.
- O erro do utilizador `ClassTweakerFormatException: Namespace (intermediary) does not match current runtime namespace (official)` ocorre em `FabricLoaderImpl.loadClassTweakers`, antes de `Knot.init`. É distinto do erro anterior `EntityType.PLAYER` e do gate de dependências.
- Inspeção do diff do commit `8d59966`: corrige a deteção de recursos com BOM, reconhecendo `.classtweaker` / `.accesswidener` pelo nome e usando o cabeçalho como fallback; o parser remove o carácter UTF-8 BOM real. A causa anterior era a filtragem do conteúdo com uma sequência literal em vez do carácter BOM.
- O teste de JAR aninhado existente foi reforçado com um BOM real no recurso `fabric-biome-api-v1.classtweaker` dentro de `META-INF/jars/fabric-biome-api-v1.jar`; verifica que o conteúdo staged passa a `official` e que o JAR original permanece byte a byte igual.
- Ficheiros de código/teste alterados nesta correção já estão no commit `8d59966664216b659580d233bce0138fd084f0e1`: `src/main/java/dev/gafipro/gafiloader/LegacyModCompatibilityTransformer.java` e `src/test/java/dev/gafipro/gafiloader/LegacyClassTweakerRemapperTest.java`. Esta sessão não alterou esses ficheiros.
- Validação nesta sessão: inspeção estática do commit/diff, código atual e teste; comparação confirma que o commit está em `main`. Não foi possível obter uma execução CI associada ao commit através das funções GitHub disponíveis (a consulta de runs devolveu lista vazia e os checks de commit não forneceram estados). Não foi executado Gradle localmente e não houve arranque Minecraft.
- Nenhuma release/instalador novo foi obtido nesta sessão. O EXE `0.1.631` referido na entrada anterior não contém a correção BOM e não deve ser apresentado como build para validar este erro.
- Próxima ação: confirmar CI do commit `8d59966`, obter o instalador dessa árvore e validar no preflight a contagem de ClassTweakers remapeados e o uso de `.gafiloader/compat-mods`. Runtime permanece não validado.


### 2026-11-01 — Retomar a falha ClassTweaker `<init>` e corrigir deteção de wildcards Fabric

- Reabertos antes de editar: `PROJECT_STATUS.md`, `README.md`, `build.gradle`, `settings.gradle`, `LegacyClassTweakerMappings.java`, `LegacyClassTweakerRemapper.java`, `LegacyClassTweakerRemapperTest.java`, `LegacyDependencyOverrideGenerator.java`, `LegacyMinecraftVersionDetector.java`, os testes de deteção/overrides e `.github/workflows/build.yml`.
- Confirmado o branch principal `main` e a cabeça `72c4215349d43402505eb454aa83bfa19bfa7eeb). A PR #26 está merged; o seu merge commit é `4c56e694471698415d4a07d9ba0a7cb8b501ea89`.
- O erro do utilizador refere literalmente `<init>` e o descritor `(ZFLnet/minecraft/class_1959$class_5484;F)V`. A correção em `LegacyClassTweakerMappings.mapMember` preserva o nome JVM e mapeia o proprietário e os tipos do descritor. O teste `remapsConstructorOwnerAndDescriptorWhenIntermediaryMappingsOmitInit` reproduz exatamente esse proprietário/descritor e verifica a saída Mojang.
- CI da árvore `72c4215`: run [41225852403](https://github.com/GafiPro/GafiLoader/actions/runs/41225852403), conclusão `success`. Ubuntu executou `clean test build`; Windows executou `clean test installDist`; ambos mostram `BUILD SUCCESSFUL`. Windows também empacotou a app image e o instalador.
- Artefacto Windows descarregado e inspecionado: `GafiLoader-0.1.745.exe`, 51 475 456 bytes, SHA-256 `40dd956c0404bb9767d03b38cfe7022ea067fad8a2f0cd27c13bccfa75828099`. O digest do ZIP Actions confere com o valor publicado. O EXE não foi executado neste ambiente Linux.
- Auditoria estática de `LegacyMinecraftVersionDetector` encontrou um caso não coberto: Fabric documenta wildcards por componente (`26.x`, `26.2.x`, `26.2.*`), mas o detetor só reconhecia `*` ou `x` quando eram o token inteiro. Num array OR como [`~1.21.11`, `26.2.x`], podia concluir incorretamente que 26.2 não era admitido e remover a dependência Minecraft.
- Fonte oficial consultada: https://docs.fabricmc.net/develop/loader/fabric-mod-json — secção Semantic Versioning; documenta arrays como alternativas OR e as formas `.x` / `.*`.
- Alteração preparada na branch `fix/fabric-version-wildcard-target-detection`: `LegacyMinecraftVersionDetector.java` reconhece wildcards numéricos por prefixo; `LegacyMinecraftVersionDetectorTest.java` cobre `26.2.x`, `26.x`, `26.2.*` e uma alternativa `26.1.x` que não deve admitir 26.2. Commits da branch: `ff5109edd85e3332829cbbf1e8767187bb83bb3d` e `8d4900d59cb1e09c375f6b30a8dc75e6f5ed5812`. PR [#27](https://github.com/GafiPro/GafiLoader/pull/27) aberto contra `main`.
- Validação desta alteração: não foi executado Gradle localmente (ambiente de trabalho remoto sem checkout/Java 25). A CI da PR #27 foi iniciada em 2026-11-01 às 10:41 UTC; estava `in_progress` no último check. Não integrar nem afirmar sucesso até ambos os jobs concluírem.
- Limitação: não foi feito arranque Minecraft real com o instalador 0.1.745; a correção ClassTweaker continua por confirmar no runtime do utilizador.
- Próximos passos: (1) verificar a CI da PR #27 e corrigir qualquer falha; (2) integrar se ambos os jobs passarem; (3) disponibilizar o instalador mais recente e validar o preflight real; (4) investigar o primeiro erro seguinte, se existir.


### 2026-11-01 — Wildcard Fabric integrado após CI

- PR [#27](https://github.com/GafiPro/GafiLoader/pull/27) foi integrada em `main` às 10:44:56 UTC; merge commit `583ef113d93f35bb0a662dddb07b6350f4175c1b`.
- Alterações: `src/main/java/dev/gafipro/gafiloader/LegacyMinecraftVersionDetector.java` reconhece wildcards de componente ao verificar se uma alternativa admite Minecraft 26.2; `src/test/java/dev/gafipro/gafiloader/LegacyMinecraftVersionDetectorTest.java` acrescenta regressões para `26.2.x`, `26.2.X`, `26.x`, `26.2.*` e `26.1.x`.
- Motivação: a especificação oficial Fabric (https://docs.fabricmc.net/develop/loader/fabric-mod-json, secção Semantic Versioning) define arrays de ranges como OR e suporta wildcards por componente. Antes da correção, o detetor não reconhecia `26.2.x` dentro de um array e podia remover desnecessariamente a dependência Minecraft quando outra alternativa já admitia 26.2.
- CI da PR: run [41298700072](https://github.com/GafiPro/GafiLoader/actions/runs/41298700072), sucesso em Ubuntu e Windows; testes/build passaram nos dois sistemas e o Windows criou app image e instalador.
- CI após merge em `main`: run [41298829441](https://github.com/GafiPro/GafiLoader/actions/runs/41298829441), conclusão `success`; Ubuntu `clean test build`, Windows `clean test installDist`, app image e instalador passaram.
- Artefacto Windows da `main`: `GafiLoader-0.1.749.exe`, 51 475 456 bytes, SHA-256 `d7d1f6f25da573c1ec0c06676f1debcd0fc39b9e4ff5636fe61a0fc3b7f096ca`. Digest ZIP Actions `319be4591e977c467fdb9185aab75b82a451ed8010ae7be54d7f28bd4c4ff0f0` verificado localmente.
- Validação local: não foi executado Gradle localmente; o ambiente de trabalho não tem checkout/Java 25. Os testes/build/packaging acima são resultados da GitHub Actions. O EXE foi descarregado e o hash/estrutura ZIP verificados, mas não foi executado em Windows neste ambiente.
- Estado do runtime: não há ainda logs posteriores ao erro ClassTweaker de 2026-10-31. O erro tem agora uma correção específica integrada e testada; a confirmação no Minecraft real continua pendente.
- Próximos passos: instalar/testar o EXE 0.1.749 no perfil dedicado, recolher `gafiloader-preflight.log` e `latest.log`, confirmar que a transformação de ClassTweaker conclui e investigar o primeiro erro seguinte, se existir.


### 2026-11-01 — Registo de falhas em todo o bootstrap

- Teste real no PC do utilizador com GafiLoader 0.1.745: o ficheiro logs/gafiloader-preflight.log termina imediatamente após a linha que identifica Java 25.0.1 (Microsoft); o utilizador confirma que o Minecraft não abre e que não existe qualquer linha adicional.
- Auditoria estática de LaunchBootstrap.main: depois de escrever a versão Java, o bootstrap lia config/fabric_loader_dependencies.json e percorria a pasta mods antes de entrar em caminhos que registavam falhas. Uma exceção de I/O/permissões nessa zona podia terminar o processo sem stack trace no preflight. Esta é uma lacuna de diagnóstico confirmada; não é ainda a causa raiz confirmada do bloqueio do utilizador.
- Correção preparada em LaunchBootstrap.java: envolver toda a execução do bootstrap num handler de Exception | Error, registar a etapa exata, escrever a stack trace completa no preflight e no stderr, e acrescentar mensagens antes/depois da leitura da configuração e antes da análise dos JARs. Removido o registo duplicado de falhas no handoff para Fabric; o handler global passa a ser o único responsável pela stack trace.
- Regressão em LaunchBootstrapTest: verifica que uma falha não tratada regista tanto a etapa como a exceção completa.
- Estado desta atualização: alterações preparadas para um único commit; a validação de CI ainda tem de terminar. Não afirmar que o Minecraft já arranca.
- Próximo passo após CI verde: gerar e instalar o novo instalador, repetir o arranque uma vez e recolher o preflight atualizado. O novo log deverá identificar a etapa e a stack trace se o bootstrap terminar por uma exceção Java; se continuar a terminar exatamente após Java, investigar encerramento externo da JVM/launcher ou falha nativa antes do handler.


### 2026-11-01 — Corrigir a referência Maven da biblioteca bootstrap

- Erro reportado no Launcher: tentou descarregar `dev/gafipro/gafiloader-bootstrap/0.1.745/gafiloader-bootstrap-0.1.745.jar` de `https://maven.fabricmc.net/`, com `Exists: Nonexistent`.
- Causa de configuração confirmada no código: `ProfileBootstrapInstaller` copia o bootstrap embutido localmente para `.minecraft/libraries/dev/gafipro/gafiloader-bootstrap/<version>/`, mas também acrescentava `url=https://maven.fabricmc.net/` ao objeto library. O projeto não publica esse artefacto no Maven do Fabric; a URL era incorreta.
- Correção: deixar de definir URL Maven para a biblioteca GafiLoader. O ficheiro é um artefacto local, instalado pela aplicação GafiLoader no diretório `.minecraft/libraries`; a versão JSON referencia a coordinate correspondente. Foi acrescentado teste que verifica que a biblioteca bootstrap não tem `url` externa.
- Clarificação operacional: instalar o EXE da aplicação GafiLoader apenas atualiza a aplicação. É necessário voltar a executar GafiLoader a partir do menu Iniciar para que a aplicação copie o novo JAR e atualize o `gafiloader-26.2.json`; depois fechar/reabrir o Launcher. A versão `0.1.745` reportada pelo Launcher indica que o perfil em disco continua a referenciar essa build, e não prova que a aplicação `0.1.751` já tenha reparado o perfil.
- Estado: alteração preparada; aguarda CI Linux/Windows e novo instalador. A remoção da URL externa corrige a configuração incorreta, mas não substitui a necessidade de executar a aplicação para instalar o JAR local e atualizar a referência de versão.
- Próximo passo: instalar o novo EXE da aplicação, executar GafiLoader uma vez com o Minecraft Launcher completamente fechado, confirmar no `versions/gafiloader-26.2/gafiloader-26.2.json` que a library coordinate corresponde à nova versão e que o respetivo JAR existe em `libraries/dev/gafipro/gafiloader-bootstrap/<version>/`.


### 2026-11-01 — Incluir a classe interna UnsafeArchiveException no bootstrap

- Log real do utilizador com bootstrap 0.1.752: falha em `generating Fabric dependency overrides` com `NoClassDefFoundError: dev/gafipro/gafiloader/LegacyDependencyOverrideGenerator$UnsafeArchiveException`.
- Causa confirmada: `build.gradle` incluía apenas `LegacyDependencyOverrideGenerator.class` e `LegacyDependencyOverrideGenerator$Result.class` no JAR mínimo de bootstrap. A classe privada aninhada `UnsafeArchiveException` foi compilada, mas ficou fora do artefacto inserido no classpath Minecraft.
- Correção: alterar o filtro do Gradle para `LegacyDependencyOverrideGenerator*.class`, incluindo todas as classes internas do gerador, e exigir `LegacyDependencyOverrideGenerator$UnsafeArchiveException.class` na validação do JAR no instalador.
- Regressões: `BootstrapJarPackagingTest` verifica a presença da exceção interna no JAR realmente embutido; `ProfileBootstrapInstallerTest` verifica que um JAR que omite essa classe é rejeitado antes de alterar o perfil ou registar a biblioteca.
- A falha é de empacotamento do GafiLoader, não dos mods do utilizador. A versão 0.1.752 não deve ser usada para validar o arranque; é necessário o novo instalador depois da CI Linux/Windows passar.
- Próximo passo: executar CI, descarregar o novo instalador e testar. A primeira confirmação esperada no novo preflight é ultrapassar a etapa `generating Fabric dependency overrides`.


### 2026-11-01 — Preservar JARs de bootstrap antigos durante atualizações

- O utilizador recebeu um erro de download para `gafiloader-bootstrap-0.1.752.jar` através do repositório Maven padrão `libraries.minecraft.net`, depois de a aplicação ter sido atualizada para a série 0.1.753.
- A causa provável foi identificada no ciclo de instalação: depois de escrever o perfil com a coordinate nova, `ProfileBootstrapInstaller` apagava as versões antigas não referenciadas no JSON em disco. Um Minecraft Launcher que já estivesse aberto podia continuar a usar a versão anterior em memória (0.1.752) e pedir exatamente o JAR que a atualização acabara de apagar.
- Correção: deixar de eliminar automaticamente versões anteriores do bootstrap. O instalador conserva os pequenos JARs históricos para que coordenadas antigas ainda mantidas em memória continuem resolúveis durante uma atualização. O log de setup explicita agora que as versões anteriores foram preservadas.
- Regressão: o teste anteriormente destinado à limpeza passa a confirmar que uma versão antiga não referenciada continua presente depois da atualização, reproduzindo o cenário de cache do Launcher.
- Esta é uma correção preventiva para a falha da biblioteca em falta; não altera o código do bootstrap nem afirma que a etapa de compatibilidade dos mods já passou.
- Próximo teste: instalar a nova aplicação, executá-la com o Launcher fechado, confirmar setup success e reabrir o Launcher completamente. Confirmar que a coordinate no JSON aponta para a nova versão e que o JAR correspondente existe localmente.


### 2026-11-01 — Diagnosticar e adaptar o Mixin de SystemReport da Fabric API

- Novo latest.log fornecido pelo utilizador mostra que Minecraft 26.2 passou a resolução de dependências e carregou 48 mods, mas caiu ao aplicar fabric-crash-report-info-v1.mixins.json:SystemReportMixin.
- A falha concreta é @Shadow setDetail(String, Supplier) não encontrado em net.minecraft.SystemReport. O log confirma o descritor legado (Ljava/lang/String;Ljava/util/function/Supplier;)V e que o target existe, mas não tem esse método/descritor.
- Comparação do código oficial Fabric API: o ramo 1.21.11 declara SystemReport#setDetail(String, Supplier<String>); o ramo 26.2 declara SystemReport#setDetail(String, CrashReportDetail<Object>). A mudança é incompatibilidade de descritor, não falha de download, nem falha do gerador de dependency overrides.
- CrashReportDetail é baseado em Callable; por isso, a lambda do código legado também tem de mudar do call-site Supplier.get() para CrashReportDetail.call().
- Alteração integrada em `LegacyMixinBytecodeAdapter`: detetar mixins que apontam para `net.minecraft.SystemReport` e contêm o `@Shadow` legado; remapear `Supplier` para `net.minecraft.CrashReportDetail` e o nome SAM do `invokedynamic` de `get` para `call`. O âmbito é target-aware para não mudar `Supplier` noutros mixins. A assinatura de `invokedynamic` foi ajustada à API ASM 9.9+.
- Teste de regressão preparado com um fixture bytecode que reproduz o shadow antigo e a lambda, verificando descritor da declaração, descritor da invocação, tipo de retorno do call-site e nome call.
- Estado: alteração integrada em main; CI ainda pendente. Não afirmar que o crash está resolvido no jogo real. Próximo passo: validar a CI Windows/Linux e gerar o artefacto; depois verificar no novo preflight que a Fabric API usa os JARs staged e volta a avançar para o próximo erro (se existir).

- Revisão posterior da API ASM 9.9.1: o remapeamento de invokedynamic passa pela sobrecarga com `Handle` e argumentos do bootstrap. A implementação foi ajustada para substituir a sobrecarga antiga de dois argumentos pela assinatura usada por `ClassRemapper`/`MethodRemapper` em ASM 9.9+; o teste de fixture continua a verificar o nome e descritor do call-site. A CI continua por validar.

- A primeira CI da adaptação `SystemReport` revelou que o fixture de mappings isolado do novo teste falhava ao carregar. Simplifiquei o teste para reutilizar o fixture Tiny/ProGuard já usado pelos testes existentes, acrescentando apenas uma classe `SystemReport` ao mesmo fixture; a regra de nome `setDetail` é agora explicitamente limitada ao target e descritor antigos exatos. A CI desta revisão ainda tem de confirmar o teste.

- A CI da revisão de fixture passou a falhar numa asserção mais tardia: o nome do call-site invokedynamic ainda era `get`. Confirmei pelo comportamento de `ClassRemapper` que a sobrecarga chamada pelo remapeador é `mapInvokeDynamicMethodName(String, String)`; a alteração anterior para a sobrecarga com `Handle` não era invocada pelo caminho efetivo e foi revertida. O teste de regressão exige agora `call` explicitamente.
