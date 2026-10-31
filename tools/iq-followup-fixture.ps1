param([int]$Port=25721)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/iq-session.ps1" -Port $Port
foreach($pos in @('199 152 28','201 152 28','199 154 28')){
 $glow=if($pos -eq '201 152 28'){'1b'}else{'0b'}
 $color=if($glow -eq '1b'){'white'}else{'black'}
 $text='{messages:["READ ME","Sharp glyphs","0123456789","Reflexium"],color:"'+$color+'",has_glowing_text:'+$glow+'}'
 Call command @{command=('data merge block '+$pos+' {front_text:'+$text+',back_text:'+$text+'}')}|Out-Null
}
Call command @{command='tick step 4'}|Out-Null
