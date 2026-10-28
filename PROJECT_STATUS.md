# GafiLoader — Registo de progresso

> Objetivo: manter um registo persistente e verificável do estado do projeto, para retomar o trabalho sem perder decisões, resultados ou próximos passos.
> Última atualização: 2026-10-28
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
