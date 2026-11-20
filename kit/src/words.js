// The words of the kit: the captions that a part writes by itself, in each language of Alphy, by their English word.
// Only the compile step reads this table (parts.js), so no word goes into the run-time text of an app.
// A word that Alphy's resources hold with the same meaning and form is a copy of it (values*/mini_app_strings.xml,
// values/mini_app_editor_english.xml, and the Russian default in values/), so that the app and the host use one word.

/** The words by language, each by its English word. English is the key itself. */
export const WORDS = {
  ru: {
    Add: "Добавить", Yes: "Да", No: "Нет", Back: "Назад", Next: "Далее", Done: "Готово", Start: "Начать", Pause: "Пауза", Reset: "Сбросить", Show: "Показать",
    Again: "Ещё раз", Check: "Проверить", Right: "Верно", Wrong: "Неверно", "Not yet": "Пока нет", Points: "Очки", "Your answer": "Ваш ответ", Rest: "Отдых",
    Work: "Работа", "Time is up": "Время вышло", Step: "Шаг", "Put together": "Собрать", "Take apart": "Разобрать",
    Remove: "Убрать", Less: "Меньше", More: "Больше", "No records yet": "Записей пока нет", "The list is full": "Список заполнен",
  },
  de: {
    Add: "Hinzufügen", Yes: "Ja", No: "Nein", Back: "Zurück", Next: "Weiter", Done: "Fertig", Start: "Starten", Pause: "Pause", Reset: "Zurücksetzen", Show: "Zeigen",
    Again: "Noch einmal", Check: "Prüfen", Right: "Richtig", Wrong: "Falsch", "Not yet": "Noch nicht", Points: "Punkte", "Your answer": "Deine Antwort", Rest: "Erholung",
    Work: "Belastung", "Time is up": "Zeit abgelaufen", Step: "Schritt", "Put together": "Zusammensetzen", "Take apart": "Zerlegen",
    Remove: "Entfernen", Less: "Weniger", More: "Mehr", "No records yet": "Noch keine Einträge", "The list is full": "Die Liste ist voll",
  },
  es: {
    Add: "Añadir", Yes: "Sí", No: "No", Back: "Atrás", Next: "Siguiente", Done: "Listo", Start: "Empezar", Pause: "Pausa", Reset: "Restablecer", Show: "Mostrar",
    Again: "Otra vez", Check: "Comprobar", Right: "Correcto", Wrong: "Incorrecto", "Not yet": "Todavía no", Points: "Puntos", "Your answer": "Tu respuesta", Rest: "Descanso",
    Work: "Trabajo", "Time is up": "Se acabó el tiempo", Step: "Paso", "Put together": "Montar", "Take apart": "Desmontar",
    Remove: "Quitar", Less: "Menos", More: "Más", "No records yet": "Aún no hay registros", "The list is full": "La lista está completa",
  },
  fr: {
    Add: "Ajouter", Yes: "Oui", No: "Non", Back: "Précédent", Next: "Suivant", Done: "Terminé", Start: "Démarrer", Pause: "Pause", Reset: "Réinitialiser", Show: "Afficher",
    Again: "Recommencer", Check: "Vérifier", Right: "Juste", Wrong: "Faux", "Not yet": "Pas encore", Points: "Points", "Your answer": "Ta réponse", Rest: "Repos",
    Work: "Effort", "Time is up": "Temps écoulé", Step: "Étape", "Put together": "Assembler", "Take apart": "Démonter",
    Remove: "Supprimer", Less: "Moins", More: "Plus", "No records yet": "Aucun enregistrement pour l'instant", "The list is full": "La liste est pleine",
  },
  it: {
    Add: "Aggiungi", Yes: "Sì", No: "No", Back: "Indietro", Next: "Avanti", Done: "Fatto", Start: "Avvia", Pause: "Pausa", Reset: "Azzera", Show: "Mostra",
    Again: "Di nuovo", Check: "Verifica", Right: "Giusto", Wrong: "Sbagliato", "Not yet": "Non ancora", Points: "Punti", "Your answer": "La tua risposta", Rest: "Riposo",
    Work: "Lavoro", "Time is up": "Tempo scaduto", Step: "Passo", "Put together": "Assembla", "Take apart": "Smonta",
    Remove: "Rimuovi", Less: "Meno", More: "Più", "No records yet": "Ancora nessun elemento", "The list is full": "La lista è piena",
  },
  ja: {
    Add: "追加", Yes: "はい", No: "いいえ", Back: "戻る", Next: "次へ", Done: "完了", Start: "開始", Pause: "一時停止", Reset: "リセット", Show: "表示",
    Again: "もう一度", Check: "確認", Right: "正解", Wrong: "不正解", "Not yet": "まだ", Points: "ポイント", "Your answer": "あなたの答え", Rest: "休憩",
    Work: "運動", "Time is up": "時間になりました", Step: "ステップ", "Put together": "組み立てる", "Take apart": "分解する",
    Remove: "削除", Less: "減らす", More: "増やす", "No records yet": "記録はまだありません", "The list is full": "リストがいっぱいです",
  },
  ko: {
    Add: "추가", Yes: "예", No: "아니요", Back: "뒤로", Next: "다음", Done: "완료", Start: "시작", Pause: "일시정지", Reset: "초기화", Show: "보기",
    Again: "다시", Check: "확인", Right: "정답", Wrong: "오답", "Not yet": "아직", Points: "점수", "Your answer": "내 답", Rest: "휴식",
    Work: "운동", "Time is up": "시간이 종료되었습니다", Step: "단계", "Put together": "조립하기", "Take apart": "분해하기",
    Remove: "제거", Less: "줄이기", More: "늘리기", "No records yet": "아직 기록이 없습니다", "The list is full": "목록이 가득 찼습니다",
  },
  pl: {
    Add: "Dodaj", Yes: "Tak", No: "Nie", Back: "Wstecz", Next: "Dalej", Done: "Gotowe", Start: "Start", Pause: "Pauza", Reset: "Resetuj", Show: "Pokaż",
    Again: "Jeszcze raz", Check: "Sprawdź", Right: "Dobrze", Wrong: "Źle", "Not yet": "Jeszcze nie", Points: "Punkty", "Your answer": "Twoja odpowiedź", Rest: "Odpoczynek",
    Work: "Praca", "Time is up": "Czas minął", Step: "Krok", "Put together": "Złóż", "Take apart": "Rozłóż",
    Remove: "Usuń", Less: "Mniej", More: "Więcej", "No records yet": "Brak wpisów", "The list is full": "Lista jest pełna",
  },
  pt: {
    Add: "Adicionar", Yes: "Sim", No: "Não", Back: "Voltar", Next: "Próximo", Done: "Concluído", Start: "Iniciar", Pause: "Pausar", Reset: "Redefinir", Show: "Mostrar",
    Again: "De novo", Check: "Verificar", Right: "Certo", Wrong: "Errado", "Not yet": "Ainda não", Points: "Pontos", "Your answer": "Sua resposta", Rest: "Descanso",
    Work: "Trabalho", "Time is up": "O tempo acabou", Step: "Passo", "Put together": "Montar", "Take apart": "Desmontar",
    Remove: "Remover", Less: "Menos", More: "Mais", "No records yet": "Ainda não há registros", "The list is full": "A lista está cheia",
  },
  tr: {
    Add: "Ekle", Yes: "Evet", No: "Hayır", Back: "Geri", Next: "İleri", Done: "Tamam", Start: "Başlat", Pause: "Duraklat", Reset: "Sıfırla", Show: "Göster",
    Again: "Tekrar", Check: "Kontrol et", Right: "Doğru", Wrong: "Yanlış", "Not yet": "Henüz değil", Points: "Puan", "Your answer": "Cevabın", Rest: "Dinlenme",
    Work: "Çalışma", "Time is up": "Süre doldu", Step: "Adım", "Put together": "Birleştir", "Take apart": "Parçalara ayır",
    Remove: "Kaldır", Less: "Azalt", More: "Artır", "No records yet": "Henüz kayıt yok", "The list is full": "Liste dolu",
  },
  uk: {
    Add: "Додати", Yes: "Так", No: "Ні", Back: "Назад", Next: "Далі", Done: "Готово", Start: "Почати", Pause: "Пауза", Reset: "Скинути", Show: "Показати",
    Again: "Ще раз", Check: "Перевірити", Right: "Правильно", Wrong: "Неправильно", "Not yet": "Ще ні", Points: "Бали", "Your answer": "Ваша відповідь", Rest: "Відпочинок",
    Work: "Робота", "Time is up": "Час вийшов", Step: "Крок", "Put together": "Зібрати", "Take apart": "Розібрати",
    Remove: "Прибрати", Less: "Менше", More: "Більше", "No records yet": "Записів поки немає", "The list is full": "Список заповнено",
  },
  zh: {
    Add: "添加", Yes: "是", No: "否", Back: "返回", Next: "下一步", Done: "完成", Start: "开始", Pause: "暂停", Reset: "重置", Show: "显示",
    Again: "再来一次", Check: "检查", Right: "正确", Wrong: "错误", "Not yet": "还没有", Points: "得分", "Your answer": "你的答案", Rest: "休息",
    Work: "训练", "Time is up": "时间到", Step: "步骤", "Put together": "组装", "Take apart": "拆开",
    Remove: "移除", Less: "减少", More: "增加", "No records yet": "暂无记录", "The list is full": "列表已满",
  },
};

/** True when the kit has its words in the language of an app: English, a language of the table, or no language. */
export const hasWords = (lang) => lang === undefined || lang === "en" || Object.hasOwn(WORDS, lang);
/** The word of the kit for an English caption in a language. A language that the table does not have gets the English word. */
export const wordOf = (lang, english) => (Object.hasOwn(WORDS, lang ?? "") && Object.hasOwn(WORDS[lang], english) ? WORDS[lang][english] : english);
