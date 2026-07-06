scoreboard players add #generator generated 3
function demo:dependency
tellraw Steve {"text":"generated command output","color":"green"}
place structure demo:template 8 64 8
data modify storage demo:generator result set value {ok:true}
