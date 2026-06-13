execute if score #clock ticks matches 20 run data modify storage demo:state phase set value "loaded20"
summon minecraft:marker ~ ~ ~ {"Tags":["demo"]}
tag @e[type=minecraft:marker,tag=demo] add active
schedule function demo:scheduled 1t replace
