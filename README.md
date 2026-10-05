# ScenePad — Android

Aplicativo Android de soundpad voltado a RPG/ambientação, com perfis, páginas, biblioteca local de áudio e ações de iluminação preparadas na arquitetura.

## O que já funciona neste projeto

- Perfis independentes (ex.: Cyberpunk, D&D, Terror)
- Várias páginas por perfil
- Linhas e colunas configuráveis por página
- Biblioteca geral com importação de áudios do celular e extração de áudio de links do YouTube usando yt-dlp e FFmpeg
- Cópia dos arquivos de áudio para o armazenamento privado do app
- Vários sons tocando simultaneamente
- Recorte por horário inicial/final (`HH:MM:SS`)
- Fade-in e fade-out configuráveis
- Volume individual por botão + volume geral
- Ajuste do volume individual por gesto vertical enquanto o botão toca
- Comportamento configurável ao tocar novamente: fade-out, instância sobreposta ou reiniciar
- Renomeação ao adicionar e depois na biblioteca
- Loop por botão
- Nome, emoji/ícone, cor e imagem customizada por botão
- Reorganização: ative o modo organizar e toque em dois botões para trocar as posições
- Botão global “Parar tudo”
- Modelo de ação de iluminação por botão: cor, brilho e efeitos STATIC/FIRE/PULSE/SUNSET

## Iluminação Positivo / Elgin

A UI e o modelo de dados já suportam ações de luz. Nesta primeira versão, `DemoLightController` é um adaptador de demonstração.

Para controlar dispositivos reais pelo Google Home APIs, é necessário:

1. Criar/registrar um projeto no Google Home Developer Console.
2. Configurar a identidade/autorização exigida pelo Home APIs.
3. Adicionar o SDK Home APIs e registrar os tipos/traits de luz suportados.
4. Substituir `DemoLightController` por uma implementação real `GoogleHomeLightController`.

O motivo de isso não estar com credenciais prontas no projeto é que as credenciais e a autorização pertencem à conta/casa do usuário e não devem ser embutidas por terceiros.

## Abrir no Android Studio

Requisitos recomendados (out/2026): Android Studio atualizado, JDK 17, Android SDK 36.

1. Abra a pasta `ScenePad` no Android Studio.
2. Aguarde o Gradle Sync baixar as dependências.
3. Rode no seu telefone Android ou emulador.

## Observações da versão 0.2

- A extração de link usa ferramentas não oficiais e depende de mudanças externas; importe apenas conteúdo que você tem autorização para salvar. Áudios de uma ou duas horas são processados em fluxo, sem carregar todo o vídeo na memória, e podem ocupar bastante espaço local.
- O motor de áudio usa `MediaPlayer` por instância, permitindo sobreposição e reprodução simultânea. Um serviço em primeiro plano e um bloqueio de suspensão mantêm os sons durante o uso em segundo plano; o Android exibe uma notificação enquanto a reprodução estiver ativa.
- Efeitos de luz ainda são simulados; nenhum comando é enviado às lâmpadas na versão 0.1.
- O editor usa segundos inteiros nos horários. Uma versão futura pode incluir waveform visual e precisão em milissegundos.
- Ainda não há exportação/importação de perfis nem backup em nuvem.
