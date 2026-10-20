# Template fixture attribution

## Anime4K

The application bundles ten unmodified MIT-licensed GLSL shaders from bloc97/Anime4K v4.0.1, commit `4029bf701ecaa15f163cdc49cffe5501c1acf410`, under `iosApp/LilacAnime/Resources/Anime4K`. The complete upstream license and copyright notice are included in that resource folder. `upstream.json` records source paths and SHA-256 checksums. These are the original mpv shaders corresponding to the desktop Anime4K.js 1.1.3 profiles, whose README identifies its models as Anime4K 4.0.1.

Source: https://github.com/bloc97/Anime4K/tree/4029bf701ecaa15f163cdc49cffe5501c1acf410

## Gemma template

The native test fixture iosApp/LilacAnimeTests/Fixtures/gemma4-template.txt is Google's Gemma 4 E4B instruction model chat template.
Author: Google DeepMind. Source: https://huggingface.co/google/gemma-4-E4B-it
The model repository declares Apache-2.0. The complete license is gemma-template-APACHE-2.0.txt.
The fixture is test data and is not bundled with the application. The filename extension is changed to .txt; the template text is unchanged apart from normalized line endings and a trailing newline.

Captured upstream commit: ee0ef6023621cff504d758262d4e04895a5af4a2
Fixture SHA-256: 0a2c8073c878ab1da004bee933a998606537bbb62016310352c7285c3f01c5b5
