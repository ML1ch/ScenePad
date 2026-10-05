# Integração futura com Google Home APIs

A versão 0.1 separa iluminação por meio da interface `LightController`.

## Objetivo

Implementar um `GoogleHomeLightController` que:

- peça autorização do usuário;
- liste estruturas/cômodos/dispositivos;
- filtre lâmpadas compatíveis;
- mapeie `deviceName` para um device ID persistente;
- use traits `OnOff`, `LevelControl` e traits de cor suportadas pelo dispositivo;
- consulte suporte antes de emitir cada comando;
- implemente efeitos FIRE/PULSE/SUNSET com transições suportadas pelo dispositivo ou com uma sequência temporizada quando necessário.

## Segurança

Não salve senha de Positivo, Elgin ou Google no aplicativo. Use o fluxo oficial de autorização do Google Home.
