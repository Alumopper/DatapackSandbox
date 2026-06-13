scoreboard objectives add rewards dummy
scoreboard players add Steve rewards 1
data modify storage demo:state rewards append value {source:"advancement",amount:1b}
tellraw @a {"text":"Steve has been awarded 1 reward point!","color":"green"}
tellraw @a {score:{name:"Steve",objective:"rewards"},color:"yellow"}