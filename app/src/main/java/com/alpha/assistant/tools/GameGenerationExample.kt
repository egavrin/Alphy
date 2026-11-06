package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject

/** One executable example supplies the prompt and the offline workflow fixture. */
internal object GameGenerationExample {
    fun spec():String=JSONObject().put("formatVersion",3).put("title","Neon Arkanoid")
        .put("description","Break 30 bricks across five rows. Drag anywhere to move the paddle, or use the native Left and Right controls. Three lives, one board, no device access.")
        .put("capabilities",JSONArray()).put("presentation",JSONObject("""{"mode":"immersive"}"""))
        .put("surfaces",JSONArray("""[{"id":"main","width":360,"height":640,"sizing":"viewport"}]"""))
        .put("stateSchema",JSONObject("""{"width":"number","height":"number","paddle":"number","x":"number","y":"number","vx":"number","vy":"number","score":"number","lives":"number","status":"string","message":"string","bricks":{"type":"array","maxItems":30,"items":"boolean"}}"""))
        .put("initialState",JSONObject().put("width",360).put("height",640).put("paddle",0.5).put("x",0.5).put("y",0.84)
            .put("vx",0.34).put("vy",-0.72).put("score",0).put("lives",3).put("status","ready")
            .put("message","Ready. Drag anywhere to move. Native Left and Right controls also move the paddle. Break all 30 bricks.")
            .put("bricks",JSONArray().apply {repeat(30) {put(true)}}))
        .put("ui",JSONObject("""{"type":"column","children":[{"type":"text","text":"Game status","bind":"message"},{"type":"text","text":"Score","bind":"score"},{"type":"text","text":"Lives","bind":"lives"},{"type":"text","text":"Drag anywhere to move the paddle. Left and Right provide accessible movement. Pause, Resume, and Restart use the same saved game."},{"type":"row","children":[{"type":"button","text":"Left","action":"left"},{"type":"button","text":"Right","action":"right"}]},{"type":"row","children":[{"type":"button","text":"Start","action":"start"},{"type":"button","text":"Pause","action":"pause"},{"type":"button","text":"Resume","action":"resume"},{"type":"button","text":"Restart","action":"restart"}]}]}"""))
        .put("source","""
            const s=input.state,e=input.event,a=e.type==='action'?e.actionId:e.type;
            if(e.type==='viewport'){s.width=e.width;s.height=e.height;}
            const W=s.width,H=s.height,u=Math.min(W,H),r=u*0.014,pw=W*0.18,py=H*0.9,ph=u*0.026;
            const clamp=v=>Math.max(0.09,Math.min(0.91,v));
            const brick=i=>({x:W*0.055+(i%6)*((W*0.89-u*0.016*5)/6+u*0.016),
              y:H*0.16+Math.floor(i/6)*u*0.08,width:(W*0.89-u*0.016*5)/6,height:u*0.046});
            const launch=()=>{s.x=s.paddle;s.y=(py-r-u*0.06)/H;s.vx=0.34;s.vy=-0.72;};
            if(a==='restart'){
              s.paddle=0.5;s.score=0;s.lives=3;s.status='playing';s.bricks=Array(30).fill(true);launch();
            }
            if((a==='start'&&s.status==='ready')||(a==='resume'&&s.status==='paused'))s.status='playing';
            if((a==='pause'||a==='stop')&&s.status==='playing')s.status='paused';
            if(a==='left'||a==='right')s.paddle=clamp(s.paddle+(a==='left'?-0.08:0.08));
            if(e.type==='control')s.paddle=clamp(s.paddle+(e.direction==='left'?-0.08:0.08));
            if(e.type==='pointer'&&(e.phase==='down'||e.phase==='move'))s.paddle=clamp(e.x/W);
            if(e.type==='frame'&&s.status==='playing'){
              for(let ms=e.dt;ms>0&&s.status==='playing';ms-=5){
                const dt=Math.min(ms,5)/1000,ox=s.x*W,oy=s.y*H;
                let x=ox+s.vx*u*dt,y=oy+s.vy*u*dt;
                if(x<r){x=r;s.vx=Math.abs(s.vx);}if(x>W-r){x=W-r;s.vx=-Math.abs(s.vx);}
                if(y<r){y=r;s.vy=Math.abs(s.vy);}
                for(let i=0;i<s.bricks.length;i++){
                  if(!s.bricks[i])continue;
                  const b=brick(i),nx=Math.max(b.x,Math.min(b.x+b.width,x)),ny=Math.max(b.y,Math.min(b.y+b.height,y));
                  if((x-nx)*(x-nx)+(y-ny)*(y-ny)>r*r)continue;
                  s.bricks[i]=false;s.score+=10;
                  const left=b.x-r,right=b.x+b.width+r,top=b.y-r,bottom=b.y+b.height+r;
                  if(ox<=left){x=left;s.vx=-Math.abs(s.vx);}
                  else if(ox>=right){x=right;s.vx=Math.abs(s.vx);}
                  else if(oy<=top){y=top;s.vy=-Math.abs(s.vy);}
                  else if(oy>=bottom){y=bottom;s.vy=Math.abs(s.vy);}
                  else {
                    const distances=[x-left,right-x,y-top,bottom-y],side=distances.indexOf(Math.min(...distances));
                    if(side===0){x=left;s.vx=-Math.abs(s.vx);}else if(side===1){x=right;s.vx=Math.abs(s.vx);}
                    else if(side===2){y=top;s.vy=-Math.abs(s.vy);}else {y=bottom;s.vy=Math.abs(s.vy);}
                  }
                  if(s.bricks.every(b=>!b))s.status='won';
                  break;
                }
                if(s.vy>0&&oy+r<=py&&y+r>=py&&x>=s.paddle*W-pw/2-r&&x<=s.paddle*W+pw/2+r){
                  const offset=Math.max(-1,Math.min(1,(x-s.paddle*W)/(pw/2)));
                  s.vx=offset*0.68;if(Math.abs(s.vx)<0.12)s.vx=s.vx<0?-0.12:0.12;
                  s.vy=-Math.sqrt(0.82*0.82-s.vx*s.vx);y=py-r;
                }
                s.x=x/W;s.y=y/H;
                if(y-r>H){s.lives--;if(s.lives===0)s.status='gameOver';else launch();break;}
              }
            }
            if(e.type!=='preview'&&e.type!=='render'){
              const messages={ready:'Ready. Drag anywhere to move. Native Left and Right controls also move the paddle. Break all 30 bricks.',
                playing:'Playing. Drag anywhere or use Left and Right.',paused:'Paused. Use Resume to continue.',
                won:'Board cleared. You won! Use Restart to play again.',gameOver:'Game over. Use Restart for a new board.'};
              s.message=messages[s.status]+' Score '+s.score+'. Lives '+s.lives+'.';
            }
            const colors=['#FF6489','#B48AFF','#648EFF','#42D9CF','#FFC66D'];
            const commands=[{type:'text',x:W*0.055,y:H*0.068,size:Math.min(128,u*0.065),text:'ARKANOID',color:'accent'},
              {type:'text',x:W*0.055,y:H*0.112,size:u*0.033,text:'SCORE '+String(s.score).padStart(3,'0')+'     LIVES '+s.lives+'     '+s.bricks.filter(Boolean).length+'/30',color:'muted'},
              {type:'line',x:W*0.055,y:H*0.13,x2:W*0.945,y2:H*0.13,stroke:Math.max(0.1,u*0.002),color:'muted'}];
            for(let i=0;i<s.bricks.length;i++)if(s.bricks[i]){
              const b=brick(i);
              commands.push({type:'roundRect',x:b.x,y:b.y+u*0.009,width:b.width,height:b.height,radius:u*0.009,color:'#020713'},
                {type:'roundRect',...b,radius:u*0.009,color:colors[Math.floor(i/6)]},
                {type:'line',x:b.x+u*0.012,y:b.y+u*0.01,x2:b.x+b.width-u*0.012,y2:b.y+u*0.01,stroke:Math.max(0.1,u*0.003),color:'#66FFFFFF'});
            }
            commands.push({type:'roundRect',x:s.paddle*W-pw/2,y:py+u*0.012,width:pw,height:ph,radius:ph/2,color:'#12384F'},
              {type:'roundRect',x:s.paddle*W-pw/2,y:py,width:pw,height:ph,radius:ph/2,
                gradient:{type:'linear',x:0,y:py,x2:W,y2:py,stops:[{offset:0,color:'#3BD9DB'},{offset:1,color:'#B6F4FF'}]}},
              {type:'line',x:s.paddle*W-u*0.025,y:py+ph/2,x2:s.paddle*W+u*0.025,y2:py+ph/2,stroke:Math.max(0.1,u*0.005),color:'#15738C'},
              {type:'circle',x:s.x*W,y:s.y*H,radius:r*2,color:'#2276DFFF'},
              {type:'circle',x:s.x*W,y:s.y*H,radius:r,color:'default'},
              {type:'text',x:W*0.055,y:H*0.975,size:u*0.027,text:'DRAG ANYWHERE  /  LEFT + RIGHT CONTROLS',color:'muted'});
            if(s.status!=='playing'){
              const titles={ready:'READY TO PLAY',paused:'PAUSED',won:'BOARD CLEARED',gameOver:'GAME OVER'};
              const details={ready:'Drag to move. Break every brick.',paused:'Your board is saved. Use Resume.',won:'Perfect clear! Use Restart to play.',gameOver:'Three lives used. Try Restart.'};
              const cw=Math.min(W*0.89,u*0.89),cx=(W-cw)/2,cy=H*0.59;
              commands.push({type:'roundRect',x:cx,y:cy,width:cw,height:u*0.24,radius:u*0.025,color:'#ED122139'},
                {type:'text',x:cx+u*0.035,y:cy+u*0.078,size:u*0.051,text:titles[s.status],color:'#E6F3FF'},
                {type:'text',x:cx+u*0.035,y:cy+u*0.14,size:u*0.031,text:details[s.status],color:'#B4C8E2'},
                {type:'text',x:cx+u*0.035,y:cy+u*0.195,size:u*0.026,text:'Native Left / Right also move the paddle.',color:'#90ABC9'});
            }
            return {state:s,playback:s.status==='won'||s.status==='gameOver'?'ended':s.status,
              surfaces:{main:{commands}},frames:s.status==='playing'?['main']:[]};
        """.trimIndent()).toString()
}
