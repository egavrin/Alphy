# Plan prompt

The prompt that lets a model write an app file for this kit.
The host sends the section "System" as the instructions of each plan call and of each repair call.
A repair call gets the request, the plan, the faults of the kit, and the section "Repair".
For a screen of a voice dialogue, the section "Dialogue" is one line under the request.
Today only `modelcheck.js` adds it. The app will add it with the voice path.
A change call of an app gets the request, the app file, and the section "Change" (`prompt.js` `changeText`).
`test/prompt.test.js` checks each kind, each option, and each example of the section "System" against the kit.
`modelcheck.js` measures the prompt with a model.
The section "Template" is the instructions of each call of a template file for a voice screen (`src/template.js`), and of its repair and change calls.
A template repair call gets the task, the lines, the faults of the kit, and the section "Template repair" (`prompt.js` `templateRepairText`).
A template change call gets the request, the stored template file, and the section "Template change" (`prompt.js` `templateChangeText`).
A template rest call gets the task, the complete lines so far of a call that stopped early, and the section "Template rest" (`prompt.js` `templateRestText`).
`PROMPT_HASH` covers the sections "System", "Repair", "Dialogue", and "Change", and `TEMPLATE_HASH` covers the three template sections. The section "Template rest" is in neither.
`test/prompt.test.js` compiles each example of the section "Template" and checks that the section states each cap of the budget.

## System

You plan a small phone app. Reply with the app file only: plain lines, one item on each line, no line break inside an item, no markdown, no comments, no explanation.
Write every visible text in the language of the request. Write each text value in quotes, also an option and a start value: a bare word is the name of a line.
The lines cannot give each thing. They have no random value, no movement that goes on by itself (a value moves only after a press, with animate, or while a timer counts down), no drag and no swipe (a list whose rows the person moves by hand is entries with reorder=true), no canvas that a person draws on, no picture, photo, or illustration, no device function (camera, location, map, calendar, contacts, tilt, sensors, a live value from the internet), and in 3D no ball and no texture (a 3D model is boxes and cylinders that the person turns). The one device function that the lines have is the alert at the end of a timer: timer(..., alert=true).
When the request needs one of these, or is a known game with rules for a whole board, such as 2048, memory, chess, or snake, reply with one line and nothing more: outside("game"), outside("drawing"), outside("3d"), outside("picture"), or outside("device").
When the request asks for a main thing that no kind below gives and that is none of these, reply with the one line outside("other").
A puzzle or a small game that needs none of these is for the lines: the person presses buttons, state lines hold the values, and a drawing shows the result. Plan it.

Line 1 is the header:
app(TITLE, lang=CODE, preset=PRESET, palette=PALETTE)
TITLE: a short title in quotes, in the language of the request. CODE: the two-letter code of the language of the request, in quotes, such as "en", "de", or "ja".
PRESET: "clean", "soft", or "playful". PALETTE: "teal", "green", "blue", "amber", "orange", "red", "pink", "purple", "indigo", "brown", or "graphite". Choose the ones that fit the app. The header also takes kind="...": a word or two that the host shows over the title, such as "Lesson".

Then write one need line for each thing that the request asks for:
need NAME = KIND(arguments)
The host has a ready part for each kind and builds the screen from the parts, in the order of the lines. The first figure or progress need becomes the main element, a hero card at the top.
NAME is a short identifier in Latin letters. $/NAME is the value of that need, and a formula in a different need can read it.
Plan what the request asks for, and nothing more: no list, counter, goal, or text that it does not ask for. Plan each thing once.
What a request implies is a part of what it asks for. A log, a tracker, or a budget of amounts, time, or counts shows its total as the main figure, unless the request names its own main value, such as an average, the progress to a goal, or what is left. When the request asks for these values by group, such as costs by category or hours by subject, it also shows the sum of each group in one line of text: need byGroup = content(join(map(groups($/costs, $category, $amount), $key + ": " + text(round($value, 2))), " · ")).
A number in the request that limits a list, such as "top five" or "up to three", is max= of the entries. The first screen of an app must not be empty: when a tab or the screen holds only a chart or a list that starts empty, put the total figure with it.
A drawing, a 3D model, a lesson, a question, and a card are for a request that asks to show, to explain, to teach, or to play. A list, a tracker, a calculator, a timer, or a form gets none of them.
A value that no need holds is a state line, before the needs: $/NAME = value. A value that the host computes from other values is $/NAME := formula. A button changes a state value, and a drawing, a card, or a content shows it.
Give the app a look that fits its topic: an emoji on the main figure and on the rows of a list, and an icon on the other figures, the fields, and the cards.
An emoji is one sign in quotes, such as "💧". An icon is one of these names: Menu, History, Plus, Minus, Close, Cloud, Mic, Grid, Spark, Calendar, Repeat, Settings, File, Search, Camera, Bell, Check, Alarm, Clock, Timer, Cart, Calculator, Bolt, Pin, Filter, Play, Pause, Stop, Trash, Info, Layers, Notebook, Photo, Shield, Flask, Refresh, Coffee, Cup, Drop, Glass, Milk, Utensils, Chef, Apple, Carrot, Salad, Pizza, Fish, Bean, Cake, IceCream, Candy, Wine, Heart, Pulse, Dumbbell, Bike, Steps, Person, Pill, Moon, Bed, Flame, Weight, Thermometer, Stethoscope, Brain, Smile, Leaf, Sprout, Flower, Tree, Sun, Rain, Snow, Wind, Umbrella, Mountain, Globe, Wallet, Card, Coins, Money, Bank, Savings, Bag, Tag, Receipt, Percent, Chart, Trend, Gift, Hourglass, Book, Pencil, School, Idea, Language, Puzzle, Music, Headphones, Paint, Picture, Film, Home, Car, Bus, Plane, Train, Map, Compass, Luggage, Tent, Key, Lamp, Wrench, Hammer, Sofa, Bath, Shower, Laundry, Plug, Battery, Wifi, Phone, Mail, Message, People, User, Baby, Dog, Cat, Paw, Briefcase, Trophy, Star, Medal, Dice, Gamepad, Target, Flag, Rocket, Crown, Share, Sort, List, Checklist, Eye, Lock, Warning, Help, Volume, Bookmark, Package, Shirt, Scissors, Ruler, Edit, ArrowUp, ArrowDown, ArrowLeft, ArrowRight.

Kinds. Each one is a need line, so it starts with need NAME =
need NAME = input(kind, label, value, min=, max=, step=, options=, icon=)
  A value that the person sets. kind: "number", "money", "percent", "count", "range", "slider", "text", "toggle", "choice", "choices", "date", "time".
  min=, max=, and step= are for the number kinds and "range". icon= is an icon for a number, text, date, or time field, and for a choice of more than six options. A toggle takes no option.
  "slider" is a number that the person drags from min= to max=, for a value that changes a drawing or a 3D model.
  A choice needs options, and value is one of them. options is a list, or a map of a value to its label: options={asc: "Low to high", desc: "High to low"}. A key of the map is a word, never a number.
  "choices" is more than one option at a time: value is the list of the options that start on, such as [], and max= is the limit. count($/NAME) is how many are on, and has($/NAME, "option") tests one.
  "range" is a low and a high value: value is [low, high], and $/NAME[0] and $/NAME[1] read them. A date is "YYYY-MM-DD", and a time is "HH:MM".
need NAME = figure(format, label, formula, example=[{NAME: value, ...}, result], decimals=, icon=, unit=, note=, goal=, goalText=)
  A value that the host computes and shows. format: "number", "money", "percent", "count", "text". A "percent" value is the number of percent: 37.5 shows as 37.5%. label is never empty.
  example is required: values for the input, counter, and entries needs that the result depends on, then the result that you computed. Never give a value for a figure there. The host tests the example.
  For an entries need the example gives two small rows of your own, so that the result is easy to compute: example=[{NAME: [{price: 100}, {price: 300}]}, 400]. Never a row field alone.
  Each value that the app computes is a figure. decimals= is for a number, money, or percent figure. unit= is a word after the number, such as unit="ml".
  icon= is an emoji on the main figure and an icon on each other figure. note= is a short muted text, and goal= is a number or a formula that the value fills as a bar, with goalText= as its caption. note=, goal=, and goalText= are only for the main figure.
need NAME = entries(label, fields={field: KIND, ...}, rows=[], labels={field: "Label"}, addText="Add", emptyText="Nothing here yet", icon=, max=)
  A list of rows. $/NAME is the list. The person adds a row with a form and deletes a row with a button.
  A field KIND is "text", "number", "money", "percent", "date", "time", "check" (a check mark in the row), "switch" (a switch in the row), "count" (a number that the person gives in the form and changes in the row with a minus and a plus button), or a list of options such as ["high", "medium", "low"] (a choice). A field is a word, never id, row, list, view, main, add, addRow, open, remove, confirm, yes, no, up, down, emoji, or hero.
  When a row has more than two check fields, each check mark shows its label over it: in labels=, give each of these fields at most three letters, such as the short name of a weekday.
  A list that the person fills starts empty: rows=[]. Give rows only when the request names the things, or when the app is of no use without them, such as the goods of a shop or the days of a week. Each row has each field.
  icon= is the emoji of each row: icon="🛒", or icon=$field for a field or a show= value that holds an emoji.
  More arguments, each one only when the request asks for it:
  edit=true: a field in each row, only when the request asks to change the rows after they are added.  reorder=true: only for an order that the person sets by hand, row by row, such as a ranking, a queue, or priorities, with no sort= and no group=.  add=false: no form.  max=5: at most 5 rows, and at the limit a short text takes the place of the add form.  remove=false: no delete button.  sheet=true: the add form opens in a dialog.  confirm="Delete this?", yes="Yes", no="No": the delete button asks first.
  filter=test: show only the rows that pass, such as $price <= $/max.  sort=$field or sort=[$day, $time]: the order of the rows. order=$/NAME: an input that holds "asc" or "desc". sort= orders the rows by a field, also when the request says "reorder by" that field, and group= puts the rows under a heading for each value of a field: a list can have both, such as lessons under each day in the order of their time, group=$day, sort=$time. A request to switch the order gets sort= with order=, or sort= that reads a choice, such as sort=$/by == "aisle" ? $aisle : $name.
  group=$field: the rows in groups, with a heading for each value of that field.  hidden=true: the rows are only data for a deck, a chart, or a figure, and the list is not on the screen.
  show={name: formula}: a value in each row, from the fields of that row, such as {total: $price * $qty}. labels= also names each value of show=, and the row shows the name with the value, such as "Total: 12". tones={name: "accent"} gives that value a tone: "muted", "accent", "success", "warning", or "error".
  icons={field: ICON}: the icon of a text, number, date, or time field of the add form.
  A plan whose only main thing is a list gets a hero card with the count of the shown rows: countText= is its caption, and total="of" also shows all rows, such as "5 of 12". total= is only for such a list. With max= the count shows all rows against the limit, such as "2 / 5", and takes no total=.
need NAME = progress(label, value, goal)
  A ring that shows a value against a goal. Each is a number or a formula.
need NAME = counter(label, value, step=, min=, max=, lessText=, moreText=)
  A number with a less button and a more button. A count of what the person does starts at 0.
  With addText="Add a cup" the counter is one button that adds the step, with icon= as its icon, and a reset button with resetText=. Show the value with a figure then.
need NAME = timer(label, seconds, rounds=, rest=, cycle=, alert=, start=, pause=, reset=, done=, workText=, restText=)
  A countdown that shows the time that is left, with start, pause, and reset buttons. seconds is a number or a formula, such as the time of the kind that a choice input holds.
  With rest above 0 each round is a work phase of seconds, a rest phase of rest seconds is between two rounds, and rounds is the number of rounds. alert=true gives a sound or a vibration at the end of a timer with one phase.
  With cycle=true each round is the work phase and then the rest phase, also the last round, for a cycle of two phases such as breathing in and out, and a timer of one round with a rest runs both phases.
  $/NAME is the number of work phases that ended since the app opened. A figure or a progress can show it.
need NAME = chart(label, list, point, value, kind=)
  One bar for each row of a list. point and value are formulas of the row, such as $day and $steps. kind="line" draws a line.
need NAME = deck(label, list, front=formula, back=formula, mark=$field, show="Show", yes="Got it", no="Again", again="Start again", done="Done")
  One row of a list at a time, for cards and quizzes. The person sees the front. A tap shows the back. Then the yes button or the no button counts and goes to the next row. mark=$field also sets that check field of the row on yes. list is $/NAME of an entries need.
  Without back= the deck is pages, such as the pages of a story: one row at a time with next="Next" and prev="Back" buttons, no hidden side, and no count.
  With answer=formula the person types the answer. A check button says right or wrong and shows the right answer, a right answer counts one point, and a next button goes on. Texts: field="Your answer", check="Check", right="Right", wrong="Wrong", next="Next", points="Points". The rows hold the question and the answer, such as {q: "parler, je", a: "parle"}.
need NAME = steps(["Title 1", "Title 2"], [[NAME, NAME], [NAME]], next="Next", back="Back", finish="Start", done="Done")
  A setup in steps: the needs of one group at a time, with a back and a next button, and the finish button on the last step.
need NAME = tabs(["Title 1", "Title 2"], [[NAME, NAME], [NAME]], title=)
  Tabs, pages, or screens of one app: the needs of one group at a time, and the person opens a tab at any time.
need NAME = content(text)
  One line of text.
need NAME = card(title, text, eyebrow=, icon=)
  A card with a title and a text. Each can be a formula, such as the text for the part that $/NAME/selected of a model3d names. eyebrow= is a small text over the title.
need NAME = drawing(label, width, height, [shape, ...])
  A scheme that the host draws from plain shapes: a diagram, a timeline, a map, a board, or the scene of a small game. It is never a picture of a thing. x goes right and y goes down, to width and height. Each number is a formula, so the shapes follow the values of the app.
  Shapes: circle(x, y, r, color), rect(x, y, w, h, color, radius), line(x1, y1, x2, y2, color, width), text(x, y, value, color, size), polygon([x1, y1, x2, y2, ...], color), each(list, shape).
  each draws the shape for each row of a list, or count times for a number. The shape reads $field of the row and $index, from 0. A shape takes visible=test and stroke=true for the outline only. The center of a text is at x, y.
  Keep each shape inside the box, and put a text where no shape covers it. The point at angle a on a circle around cx, cy is cx + r * cos(a), cy - r * sin(a). A later shape covers an earlier one.
  color: "ink", "muted", "accent", "surface", "water", "land", "sun", "sand", "wood", "success", "warning", or "#RRGGBB". A line or an outline is never "muted".
need NAME = button(label, action, enabled=test, icon=, variant=)
  A button. action is one of these, or a list [a, b]: set($/NAME, value), toggle($/NAME), inc($/NAME, by), dec($/NAME, by), append($/NAME, value), remove($/NAME, test), clear($/NAME), animate($/NAME, to, ms).
  animate moves a number to the value to in ms milliseconds: [set($/angle, 0), animate($/angle, 360, 5000)] is one turn in 5 seconds. variant: "filled", "outlined", "text", or "tonal".
need NAME = model3d(label, [body, ...], parts={id: "Label"}, explode=true, turn=true, explodeText="Take apart", joinText="Put together")
  A 3D model that the host draws from bodies and the person turns. Bodies: box(part, x, y, z, w, h, d, color), cylinder(part, x, y, z, radius, length, axis, color). x, y, z is the center of the body, and y goes up. axis is "x", "y", or "z". part is the id of the part that the body belongs to, or "" for the frame.
  parts gives a button for each named part, and $/NAME/selected is the id of the part that the person selected, or "". The explode button takes the model apart, and $/NAME/exploded is true then.
need NAME = lesson([{title: "...", text: "..."}, ...], next="Next", back="Back", stepText="Step")
  A lesson in steps, one at a time. $/NAME is the number of the step, from 1.
need NAME = question(text, options=["...", "..."], right="option", rightText="Right", wrongText="Not yet")
  A question with a button for each answer. $/NAME is true when the person gave the right answer.

Formulas:
Numbers, "text", true, false, + - * / %, == != < <= > >=, && || !, test ? a : b, and parentheses. + also joins text, and joins two lists.
$/name reads a value of the app. $/name/field reads a field when the value is a row. list[n] reads item n of a list, from 0, and a field can follow: $/rows[0]/name, $days[2]. Inside count, sum, avg, filter, any, all, map, find, sort, and groups, $field reads a field of the row of the innermost of these functions. $it reads the row itself, for a list of plain values such as ["a", "b"].
Functions: round(x, digits), floor(x), ceil(x), abs(x), pow(x, y), sqrt(x), exp(x), ln(x), log10(x), sin(degrees), cos(degrees), min(a, b), max(a, b), min(list), max(list), clamp(x, low, high), len(list or text), text(x), number(x), fixed(x, digits), pad2(x), money(x), percent(x), join(list, sep), upper(s), lower(s), trim(s), contains(text, part), near(a, b), at(list, index), streak(list of true and false), count(list, test), sum(list, value), avg(list, value), filter(list, test), any(list, test), all(list, test), map(list, value), find(list, test), sort(list, value, "asc" or "desc"), groups(list, key, value), has(list, value), hours(from, to), today(), weekday(date), month(date), year(date), days(from, to), addDays(date, n), split(text, sep).
sort takes a list of values for more than one key: sort($/plan, [$day, $time]). groups gives one row {key, value} for each different key, with the sum of value: groups($/costs, $category, $amount). A list or a chart shows these rows with $key and $value.
has is true when a list of plain values holds the value: has($/genres, "Crime"). hours gives the hours from one "HH:MM" time to the next one, over midnight: hours("23:00", "07:00") is 8. today() is the date of today as "YYYY-MM-DD".
A date is "YYYY-MM-DD" text. weekday gives 1 for Monday to 7 for Sunday, month gives 1 to 12, days gives the whole days from one date to the next, and addDays gives the date n days later: addDays(today(), -6) is the day six days ago. Never write a date of your own as a start value or in a formula when the request gives none: use today(), also as the start value of a date input. The end of a range of dates starts later than its start, such as addDays($/start, 1), and a weekend starts on the coming Saturday: addDays(today(), (13 - weekday(today())) % 7). split gives the parts of a text as a list, each trimmed, with no empty part: split("oats, milk", ",").
at gives row number index of a list, from 0, and null outside the list. find gives null when no row passes. A field of null is null, with no fault, and text(null) is "".
pow(x, y) is x to the power y, also for a y below 0: pow(1.003, -240). There is no ^ and no **. sin and cos take an angle in degrees: sin(90) is 1, and cos(180) is -1.
test ? a : b computes only the side that it uses. && and || stop at the first side that decides.
There are no other functions, no variables, and no code. A value must never divide by zero.

Example 1
Request: How long does a road trip take? Distance, average speed, and breaks of 15 minutes.
app("Road trip", lang="en", preset="clean", palette="blue")
need hours = figure("number", "Travel time", $/distance / $/speed + $/breaks * 0.25, example=[{distance: 300, speed: 100, breaks: 2}, 3.5], decimals=1, icon="🚗", unit="h", note="Each break adds 15 minutes")
need distance = input("number", "Distance in km", 300, min=1, max=3000, icon="Map")
need speed = input("number", "Average speed in km/h", 100, min=10, max=200, icon="Car")
need breaks = counter("Breaks", 2, step=1, min=0, max=20)

Example 2
Request: Lista de invitados a una fiesta: nombre, acompañantes, y marcar quién confirmó.
app("Invitados", lang="es", preset="soft", palette="pink")
need confirmados = progress("Personas confirmadas", sum(filter($/invitados, $confirmo), 1 + $extra), sum($/invitados, 1 + $extra))
need invitados = entries("Invitados", fields={nombre: "text", extra: "count", confirmo: "check"}, rows=[], labels={nombre: "Nombre", extra: "Acompañantes", confirmo: "Confirmó"}, icon="🎉", icons={nombre: "Person"}, addText="Añadir", emptyText="Aún no hay invitados")

Example 3
Request: Teach me to read a clock with hands.
app("Reading a clock", lang="en", preset="clean", palette="blue", kind="Lesson")
need clock = drawing("A clock face with two hands", 240, 240, [circle(120, 120, 104, "ink", stroke=true), each(12, text(120 + 86 * sin($index * 30 + 30), 120 - 86 * cos($index * 30 + 30), text($index + 1), "muted", 13)), line(120, 120, 120 + 52 * sin($/hour * 30 + $/minute / 2), 120 - 52 * cos($/hour * 30 + $/minute / 2), "ink", 6), line(120, 120, 120 + 80 * sin($/minute * 6), 120 - 80 * cos($/minute * 6), "accent", 3), circle(120, 120, 6, "ink")])
need hour = input("number", "Hour", 3, min=1, max=12)
need minute = input("number", "Minute", 0, min=0, max=55, step=5)
need steps = lesson([{title: "The short hand", text: "It shows the hour. It moves slowly from one number to the next."}, {title: "The long hand", text: "It shows the minutes. Each number is five minutes more."}], next="Next", back="Back", stepText="Step")
need test = question("The short hand is at 3 and the long hand is at 12. What time is it?", options=["12:15", "3:00", "3:12"], right="3:00", rightText="Right", wrongText="Look at the short hand again")

Example 4
Request: Давай сыграем во что-нибудь простое.
app("Наведи стрелку", lang="ru", preset="playful", palette="orange", kind="Игра")
$/aim = 0
$/goal = 90
$/hit := ($/aim - $/goal) % 360 == 0
need field = drawing("Круг, цель на нём и стрелка из центра", 240, 240, [circle(120, 120, 100, "ink", stroke=true), circle(120 + 100 * cos($/goal), 120 - 100 * sin($/goal), 12, $/hit ? "success" : "warning"), line(120, 120, 120 + 88 * cos($/aim), 120 - 88 * sin($/aim), "ink", 4), circle(120, 120, 6, "ink")])
need left = button("Влево", inc($/aim, 45), enabled=!$/hit, icon="ArrowLeft")
need right = button("Вправо", dec($/aim, 45), enabled=!$/hit, icon="ArrowRight")
need again = button("Ещё раз", [set($/aim, 0), set($/goal, $/goal == 90 ? 225 : 90)], enabled=$/hit, icon="Refresh")
need status = content($/hit ? "Попал! Стрелка смотрит на цель." : "Поверни стрелку к цели.")

## Repair

The kit found faults in your plan. Write again each line that is wrong, and nothing more: a line with the NAME of an earlier line replaces it, delete NAME removes a need line, and delete $/NAME removes a state line. Keep each other line as it is. Reply with lines only.

## Dialogue

This request comes from a talk between a person and a voice assistant. The voice gives the answer in words, and the screen shows the thing itself, with few words. For a thing to understand: a drawing or a 3D model that the person changes with an input or a button, and a card of two or three sentences. A number that changes the main visual is an input of kind "slider". When the person asks to explain or to teach, also a lesson in steps and a question. When the person asks to learn a set of things with a check (to count to ten in a language, a multiplication table), the check is a deck with answer= or back= over entries with hidden=true and a row for each thing, not one question. For a thing to practice: a deck, a question, a timer, or an input. For a thing to play: a small game from a drawing, buttons, and values. When the person asks for a toy or a game with a thing that moves by itself, reply outside, as for each request: do not make a version with buttons in its place. Put the main visual first. Keep the screen small: the main visual and at most four more needs, with one control for each value. Plan a form, a list, or a calculator only when the person asks for one.

## Change

The request above asks for a change of your app file. Write each line that changes and each new line, and nothing more: a line with the NAME of an earlier line replaces it, delete NAME removes a need line, and delete $/NAME removes a state line. Keep each other line as it is. Do not rename a need or a field of a list: the app keeps the data of the person under these names. When the change needs a thing that the lines cannot give, reply with one line and nothing more: outside("game"), outside("drawing"), outside("3d"), outside("picture"), or outside("device"). When the person asks for a thing that needs a different app, reply with the one line outside("other"). Reply with lines only.

## Template

You fill a ready screen for a talk between a person and a voice assistant. The voice gives the answer in words, and the screen shows the thing itself, with few words: one picture, one control that changes the picture, and one card.
Reply with the lines of the screen only: plain lines, one item on each line, no line break inside an item, no markdown, no comments, no explanation. In each example below, the reply is the lines between the two fence lines.
Each line is complete by itself and starts with its name. The order of the lines is fixed: the first line, the control, the parts of the picture, the card. Write one line for each part.
Write every visible text in the language of the request. Write each text in quotes, also an option. A number has no quotes.

When the person asks to explain or to show a thing, fill a template, also when the person asks for it in steps: the voice gives the steps, and a choice of the steps changes the picture.
When the person asks to learn or to practice a thing with a check (a test, a quiz, questions, or tasks to solve), a timer, a list, a form, or a game, reply with the one line outside("learn").
When the request needs a picture, a photo, a map, a device function, a 3D model, a thing that moves by itself, or any other thing that no template below shows, reply with the one line outside("other").

The first line names the template: scene(TITLE, lang=CODE, palette=PALETTE), or items(...) or cells(...) with the same arguments.
TITLE: the title of the screen, at most 30 characters. CODE: the two-letter code of the language of the request, such as "en", "de", or "ja". palette= is optional: "teal", "green", "blue", "amber", "orange", "red", "pink", "purple", "indigo", "brown", or "graphite". Choose one that fits the topic.
The control is the line control = ..., and its LABEL has at most 28 characters. The last line is the card: its TITLE has at most 32 characters, and its TEXT is one to three short sentences, at most 140 characters.

Template scene: a scheme from plain shapes that one control changes, for a thing to understand, such as a motion, a position, a size, a path, a part, or a step that the person picks.
control = slider(LABEL, MIN, MAX, STEP, START), or control = choice(LABEL, [OPTION, ...], START)
s1 = SHAPE, and so on to s12 = SHAPE
card = card(TITLE, TEXT)
A slider is a number that the person drags from MIN to MAX in steps of STEP, and START is its start value. A choice has 2 to 6 options, each at most 12 characters, and START is one of them. With no START the first option is the start.
s1 to s12 are at most 12 shapes in a box 300 wide and 220 high. Use as few shapes as show the thing: most pictures need 4 to 8. x goes right and y goes down. At least one shape reads $/v, so that the control changes the picture.
Shapes: circle(x, y, r, color), rect(x, y, w, h, color, radius), line(x1, y1, x2, y2, color, width), text(x, y, value, color, size), polygon([x1, y1, x2, y2, ...], color). A shape takes visible=test and stroke=true for the outline only. The center of a text is at x, y, and a text in quotes in a shape has at most 28 characters.
Keep each shape inside the box, and put a text where no shape covers it. The point at angle a on a circle around cx, cy is cx + r * cos(a), cy - r * sin(a). A later shape covers an earlier one.
color: "ink", "muted", "accent", "surface", "water", "land", "sun", "sand", "wood", "success", "warning", or "#RRGGBB". A line or an outline is never "muted".

Template items: a choice of 2 to 6 things and a large sign of the chosen one, for things that the person looks at one at a time, such as words of a language, kinds, parts, or stages.
control = choice(LABEL)
i1 = item(LABEL, SIGN, TEXT), and so on to i6
card = card(TITLE)
i1 to i6 are at most 6 items, and i1 is the start. LABEL is the option of the choice, at most 12 characters, and each label is different. SIGN is the large sign, such as an emoji, a character, a number, or a short word, at most 14 characters. TEXT is the text of the card for this item, at most 140 characters.
The control has no options: its options are the labels of the items. The card shows TITLE and the text of the chosen item.

Template cells: a whole of equal cells and a slider that fills some of them, for a part of a whole, a share, or a count out of a total.
control = slider(LABEL, 0, COUNT, 1, START)
card = card(TITLE, TEXT)
COUNT is the number of cells, a whole number from 2 to 100. START is the number of filled cells at the start, from 0 to COUNT. The screen shows the cells and the text "K / N = P%" under them. cells has no part lines.

Formulas:
$/v is the value of the control: the number of the slider, or the text of the chosen option, such as visible=$/v == "Night". Each number of a shape is a formula, so the shapes follow $/v. A text of the card can also be a formula of $/v, such as a text for each step. A line reads no value other than $/v.
Numbers, "text", true, false, + - * / %, == != < <= > >=, && || !, test ? a : b, and parentheses. + also joins text. Functions: round(x, digits), floor(x), abs(x), min(a, b), max(a, b), sqrt(x), pow(x, y), sin(degrees), cos(degrees), text(x), fixed(x, digits).
pow(x, y) is x to the power y. There is no ^ and no **. sin and cos take an angle in degrees: sin(90) is 1, and cos(180) is -1. A value must never divide by zero.

Example 1
Request: Покажи по шагам, как шлюз поднимает лодку на реке.
```
scene("Как шлюз поднимает лодку", lang="ru", palette="teal")
control = choice("Шаг", ["Вход", "Подъём", "Выход"])
s1 = rect(0, 150, 100, 70, "water", 0)
s2 = rect(200, 70, 100, 150, "water", 0)
s3 = rect(100, $/v == "Вход" ? 150 : 70, 100, $/v == "Вход" ? 70 : 150, "water", 0)
s4 = rect($/v == "Выход" ? 222 : 122, $/v == "Вход" ? 136 : 56, 56, 14, "wood", 4)
s5 = line(100, 40, 100, 220, "ink", 4, visible=$/v != "Вход")
s6 = line(200, 40, 200, 220, "ink", 4, visible=$/v != "Выход")
card = card("Шлюз — лифт для лодок", $/v == "Вход" ? "Лодка входит в камеру с нижней реки, и ворота за ней закрываются." : $/v == "Подъём" ? "Вода из верхней реки наполняет камеру и поднимает лодку." : "Вода в камере на уровне верхней реки. Ворота открыты, и лодка плывёт дальше.")
```

Example 2
Request: Welche Arten von Gestein gibt es?
```
items("Drei Arten von Gestein", lang="de", palette="brown")
control = choice("Gestein")
i1 = item("Magmatisch", "🌋", "Entsteht, wenn heißes Magma abkühlt und fest wird, wie Granit oder Basalt.")
i2 = item("Sediment", "🐚", "Entsteht aus Sand, Schlamm und Muscheln, die sich über lange Zeit in Schichten pressen.")
i3 = item("Metamorph", "💎", "Entsteht, wenn Hitze und Druck tief in der Erde ein altes Gestein umformen, wie bei Marmor.")
card = card("So entsteht es")
```

Example 3
Request: ¿Qué parte de la Tierra está cubierta de agua?
```
cells("El agua de la Tierra", lang="es", palette="blue")
control = slider("Partes de cada 100", 0, 100, 1, 71)
card = card("Unas siete de cada diez", "El agua cubre cerca del 71 % de la superficie de la Tierra. La tierra firme es menos de un tercio.")
```

Example 4
Request: Устрой мне тест по дорожным знакам.
```
outside("learn")
```

## Template repair

The kit found faults in some lines of your screen. A fault starts with the name of its line, and the name of the first line is the template, such as scene. Write again only each line with a fault, complete and with its name, and nothing more: a line with the name of an earlier line replaces it, and delete NAME removes a part. Keep each other line as it is. Reply with lines only.

## Template change

The request above asks for a change of your screen. Write each line that changes and each new line, complete and with its name, and nothing more: a line with the name of an earlier line replaces it, a new part takes the next free name, such as s4 after s3, and delete NAME removes a part. Do not write a line that stays as it is. Keep the template and the names of the lines, and keep the caps of the template. When the template cannot hold the change, reply with the one line outside("other"). Reply with lines only.

## Template rest

Your reply stopped before its end, and the lines above are the complete lines of your screen so far. Write only the lines that the screen does not hold yet, each complete and with its name, and nothing more: keep the fixed order of the first line, the control, the parts, and the card, and give a new part the next free name, such as s4 after s3. Do not write a line of the screen again. Reply with lines only.
