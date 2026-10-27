# GafiLoader — Registo de progresso

> Objetivo: manter um registo persistente e verificável do estado do projeto, para retomar o trabalho sem perder decisões, resultados ou próximos passos.
> Última atualização: 2026-10-27
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
