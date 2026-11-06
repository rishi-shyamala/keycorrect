package dev.keycorrect.sampleplugin

/**
 * The toy language data of the sample engine, written by hand for this sample (nothing downloaded).
 * The numbers are made up: a word's rank in a list stands in for its frequency. It is enough to
 * make "thank you" more likely than "thank banana", which is all a sample needs.
 */
internal object BigramData {
    /** The context "word" at the start of a text and after sentence-ending punctuation. */
    const val START = "<s>"

    /** Common English words, most frequent first; the rank sets the unigram count. */
    val vocabulary: List<String> =
        """
        the to and of a i in you is that it for on was with he be have this are not but at as
        we they my me so do will can just if or what all there out up about from one would like
        get go your when know no time now then how see good her him his she them their by an
        had were been has more some think who want which make back because could say here
        really well also day going thank thanks love need work home today tomorrow tonight
        please sorry yes okay hello hi hey great new people way even any very too much over
        after first only other than into our us still should did does am being where why come
        take give tell call feel look let right off down again never always meet morning night
        week free soon late ready happy nice sure wait lunch dinner coffee phone message text
        email meeting friend family thing things something everything anything someone many
        most made said little long last next own same those these might must may while before
        through between both each every around another such better best sound sounds hope wish
        believe remember forget keep start stop try help ask find put use mean name place year
        years thought lot bit few whole course looking forward talk luck wanted i'm don't it's
        that's can't i'll i've you're we're didn't doesn't isn't let's front able least got
        idea early birthday else old ago minutes care mind done hour problem
        """
            .trimIndent()
            .split(Regex("\\s+"))
            .distinct()

    /**
     * Lines of `previous: following following ...`, the likeliest follower first. The bigram count
     * of a follower is derived from its position in the line. Every word must be in [vocabulary] (a
     * unit test checks it).
     */
    val bigramLines: String =
        """
        <s>: i the it we you this thank hi hello please what how yes no thanks hey can do are is
        <s>: my so but i'm it's that's let's don't there if when
        the: same best first next last other new way day only most time whole one thing people
        the: meeting message phone family name place morning night week year
        to: be the see do get have go make a me you know take say meet come help call tell give
        to: find start try keep use ask put
        and: i the then we it you he they so a that my
        of: the a course my your all this our it
        a: lot little bit good great new long day nice few friend call message thing year week
        i: am have will think can would know want need love hope just didn't don't could should
        i: was had feel mean
        in: the a my this time our your front
        you: are have can will know want need should could do did don't were
        is: the a not it that going there this very so
        that: is i the was you would will we
        it: is was will would has can could to
        for: the a me you your us them now
        on: the my our this
        with: the you a my me us him them
        he: is was has will would
        be: a the there able sure ready here
        have: a to the you been some any
        this: is morning week time year
        are: you we they not going the a
        not: sure a the going really have
        but: i the it we you if
        at: the least all a home work
        as: a well soon much the
        we: are have can will should need could would
        they: are have were will
        my: name friend phone family own
        me: know a the to back
        so: much i the good sorry
        do: you not it the that
        will: be you see call
        can: you i be see do
        just: wanted a the got
        if: you i it we
        or: not the a
        what: do is are time you
        there: is are was will
        out: of there
        about: the it that
        from: the my
        one: of more
        would: be like you
        like: to a
        get: a back
        go: to home
        your: name phone
        when: you i we
        know: what that
        thank: you
        thanks: for a
        see: you it
        good: morning night luck time
        now: i you
        how: are is do
        time: to for
        going: to home
        want: to a
        need: to a
        love: you it
        i'm: not sorry going
        don't: know think want
        it's: a not
        that's: a not
        let's: go meet
        hope: you to so
        looking: forward
        talk: to soon
        wanted: to
        great: to idea
        very: much good
        too: much late
        much: better
        more: than time
        than: i
        other: than people
        first: time
        last: night week
        next: week time
        every: day time
        each: other
        same: time
        today: i is
        tomorrow: morning night
        tonight: at
        please: let call
        sorry: for i
        yes: i please it
        okay: so
        hello: how
        hi: there
        hey: how there
        home: soon
        work: today on
        early: morning
        ready: to
        free: to
        late: for night
        nice: to day
        happy: birthday to
        sure: you that
        wait: for
        lunch: today
        dinner: tonight
        coffee: tomorrow
        call: me you
        text: me
        message: me
        email: me
        meeting: tomorrow at
        friend: of
        family: and
        thing: is
        things: are
        something: like
        everything: is
        anything: else
        someone: who
        many: of
        most: of
        made: a
        said: that
        little: bit
        long: time day
        own: way
        those: are
        these: are
        might: be
        must: be
        may: be
        while: i
        before: i
        through: the
        between: the
        both: of
        around: the
        another: one
        such: a
        better: than
        best: of
        sounds: good great
        wish: you
        believe: in
        remember: to
        forget: to
        keep: in
        start: with
        stop: by
        try: to
        help: me
        ask: me
        find: out
        put: it
        use: the
        mean: it
        name: is
        place: to
        year: old
        years: ago
        thought: you
        lot: of
        bit: of
        few: minutes
        whole: day
        course: i
        forward: to
        luck: with
        should: be i
        did: you
        does: it
        am: i
        being: a
        where: are is
        why: do are
        come: back here
        take: a care
        give: me
        tell: me
        feel: free like
        look: at like
        let: me
        right: now
        off: the
        down: the
        again: soon
        never: mind
        always: a
        meet: you up
        morning: i
        night: i
        week: i
        soon: as
        really: want good
        well: done i
        also: have a
        day: i today
        here: is are
        back: to home
        because: i of
        could: you be
        say: that it
        who: is are was
        which: is
        make: a sure
        think: so about that
        some: time of
        has: been
        been: a
        were: you
        had: a to
        them: to
        their: own
        by: the tomorrow
        an: hour
        his: own
        her: own
        him: to
        she: is was
        all: the of day
        up: to and
        no: problem one
        into: the
        our: own
        us: to
        still: have
        only: one
        even: if
        any: time other
        way: to too
        people: are
        new: year one
        """
            .trimIndent()
}
