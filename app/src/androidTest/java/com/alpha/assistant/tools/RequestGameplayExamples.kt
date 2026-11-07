package com.alpha.assistant.tools

import org.json.JSONArray
import org.json.JSONObject

/** Frozen requests and executable examples for local development workflows. */
internal object RequestGameplayExamples {
    const val starCatchRequest = "Сделай игру «Собери звёзды»: звёзды падают по трём дорожкам. " +
        "Лови их корзиной: касайся дорожки или двигайся влево и вправо. " +
        "За каждую пойманную звезду дай очко. Три очка — победа, два промаха — конец игры. " +
        "Добавь начало новой игры с нуля. Все подписи на русском."

    const val orbitRequest = "Сделай мини-приложение «Орбита Луны» с движущейся Луной вокруг Земли. " +
        "Встрой сцену в экран приложения и добавь ползунок скорости от 1 до 3. " +
        "Касание сцены должно перемещать Луну к выбранной точке орбиты. " +
        "Добавь паузу и возврат в начальное положение. Все подписи на русском."

    fun starCatchSpec(): String = JSONObject()
        .put("formatVersion", 3)
        .put("title", "Собери звёзды")
        .put("description", "Поймай три звезды корзиной и не допусти двух промахов.")
        .put("capabilities", JSONArray())
        .put("presentation", JSONObject("""{"mode":"immersive"}"""))
        .put("surfaces", JSONArray("""[{"id":"field","width":280,"height":228}]"""))
        .put("stateSchema", JSONObject("""{"status":"string","lane":"number","target":"number","elapsed":"number","score":"number","misses":"number"}"""))
        .put("initialState", JSONObject("""{"status":"ready","lane":1,"target":0,"elapsed":0,"score":0,"misses":0}"""))
        .put("ui", JSONObject("""{"type":"text","text":"Коснитесь дорожки или используйте кнопки «Влево» и «Вправо». Поймайте три звезды. После двух промахов игра закончится."}"""))
        .put("source", """
            const s = input.state, e = input.event;
            const lanes = [52, 140, 228], fallMs = 700;
            if (e.type === 'restart') Object.assign(s, {status:'ready',lane:1,target:0,elapsed:0,score:0,misses:0});
            if (e.type === 'start' && s.status === 'ready' || e.type === 'resume' && s.status === 'paused') s.status = 'playing';
            if ((e.type === 'pause' || e.type === 'stop') && s.status === 'playing') s.status = 'paused';
            if (s.status === 'ready' || s.status === 'playing') {
              if (e.type === 'pointer' && (e.phase === 'down' || e.phase === 'move')) {
                s.lane = e.x < 96 ? 0 : e.x < 184 ? 1 : 2;
              }
              if (e.type === 'control') s.lane = Math.max(0, Math.min(2, s.lane + (e.direction === 'left' ? -1 : 1)));
            }
            if (e.type === 'frame' && s.status === 'playing') {
              s.elapsed += e.dt;
              if (s.elapsed >= fallMs) {
                if (s.lane === s.target) s.score += 1;
                else s.misses += 1;
                if (s.score === 3) s.status = 'won';
                else if (s.misses === 2) s.status = 'lost';
                s.elapsed -= fallMs;
                s.target = (s.target + 2) % lanes.length;
              }
            }
            const ended = s.status === 'won' || s.status === 'lost';
            const commands = [96,184].map(x => ({type:'line',x,y:30,x2:x,y2:206,stroke:1,color:'muted'}));
            commands.push(
              {type:'text',x:12,y:19,size:13,text:'Очки: '+s.score+' · Промахи: '+s.misses,color:'default'},
              {type:'roundRect',x:lanes[s.lane]-22,y:198,width:44,height:12,radius:4,color:'accent'}
            );
            if (!ended) {
              const y = 32 + 158 * s.elapsed / fallMs;
              const points = Array.from({length:10}, (_, i) => {
                const a = i * Math.PI / 5 - Math.PI / 2, r = i % 2 ? 4 : 8;
                return [lanes[s.target] + r * Math.cos(a), y + r * Math.sin(a)];
              });
              commands.push({type:'polygon',points,color:'warning'});
            }
            const message = {ready:'Нажмите «Начать»',paused:'Пауза',won:'Вы победили!',lost:'Игра окончена'}[s.status];
            if (message) commands.push({type:'text',x:77,y:130,size:15,text:message,color:'default'});
            return {state:s,surfaces:{field:{commands}},playback:ended?'ended':s.status,frames:s.status==='playing'?['field']:[]};
        """.trimIndent())
        .toString()

    fun orbitSpec(): String = JSONObject()
        .put("formatVersion", 3)
        .put("title", "Орбита Луны")
        .put("description", "Меняйте скорость Луны или выберите точку на орбите касанием.")
        .put("capabilities", JSONArray())
        .put("surfaces", JSONArray("""[{"id":"orbit","width":280,"height":200}]"""))
        .put("stateSchema", JSONObject("""{"status":"string","angle":"number","speed":"number"}"""))
        .put("initialState", JSONObject("""{"status":"ready","angle":0,"speed":1}"""))
        .put("ui", JSONObject("""
            {"type":"column","children":[
              {"type":"text","text":"Коснитесь сцены, чтобы переместить Луну."},
              {"type":"slider","text":"Скорость","bind":"speed","action":"speed","min":1,"max":3,"step":1}
            ]}
        """.trimIndent()))
        .put("source", """
            const s = input.state, e = input.event;
            const x = 140, y = 100, radius = 70, turn = 2 * Math.PI;
            const wrap = angle => angle < 0 ? angle + turn : angle % turn;
            if (e.type === 'restart') Object.assign(s, {status:'ready',angle:0,speed:1});
            if (e.type === 'start' && s.status === 'ready' || e.type === 'resume' && s.status === 'paused') s.status = 'playing';
            if ((e.type === 'pause' || e.type === 'stop') && s.status === 'playing') s.status = 'paused';
            if (e.type === 'action' && e.actionId === 'speed') s.speed = e.value;
            if (e.type === 'frame' && s.status === 'playing') s.angle = wrap(s.angle + e.dt * s.speed * 0.002);
            if (e.type === 'pointer' && (e.phase === 'down' || e.phase === 'move') && (e.x !== x || e.y !== y)) {
              s.angle = wrap(Math.atan2(e.y - y, e.x - x));
            }
            if (e.type === 'control') s.angle = wrap(s.angle + (e.direction === 'left' ? -1 : 1) * Math.PI / 6);
            const angle = s.angle;
            const commands = [
              {type:'arc',x:x-radius,y:y-radius,width:radius*2,height:radius*2,start:0,sweep:360,stroke:1.5,color:'muted'},
              {type:'circle',x,y,radius:26,color:'accent'},
              {type:'circle',x:x+radius*Math.cos(angle),y:y+radius*Math.sin(angle),radius:10,color:'warning'},
              {type:'text',x:12,y:20,size:13,text:'Земля и Луна',color:'default'}
            ];
            return {state:s,surfaces:{orbit:{commands}},playback:s.status,frames:s.status==='playing'?['orbit']:[]};
        """.trimIndent())
        .toString()
}
